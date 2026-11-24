package com.aihuiji.meeting

import com.aihuiji.meeting.core.model.AppSettings
import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.ProfileKind
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.ServerProfile
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.BackendKind
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.ProviderErrorKind
import com.aihuiji.meeting.core.provider.ProviderException
import com.aihuiji.meeting.core.provider.ProviderFactory
import com.aihuiji.meeting.core.provider.ServiceKind
import com.aihuiji.meeting.core.provider.hunyuan.HunyuanChatClient
import com.aihuiji.meeting.core.provider.hunyuan.HunyuanMinutesProvider
import com.aihuiji.meeting.core.provider.hunyuan.HunyuanPrompts
import com.aihuiji.meeting.core.provider.hunyuan.HunyuanTranslationProvider
import com.aihuiji.meeting.core.provider.hunyuan.TencentAsrCredentials
import com.aihuiji.meeting.core.provider.hunyuan.TencentAsrSession
import com.aihuiji.meeting.core.provider.hunyuan.TencentAsrSigner
import com.aihuiji.meeting.core.provider.remote.FrameSpool
import com.aihuiji.meeting.core.provider.remote.RemoteAsrSession
import com.aihuiji.meeting.core.provider.remote.RemoteClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HunyuanBackendTest {
    private lateinit var server: MockWebServer
    private val spoolDir = Files.createTempDirectory("spool").toFile()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun chat(onPrem: Boolean = false, timeoutSec: Int = 5) =
        HunyuanChatClient(server.url("/v1").toString(), { "hy-key" }, timeoutSec, onPrem)

    private fun completion(content: String) =
        MockResponse().setBody("""{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), content)}}}]}""")

    private inline fun expectError(block: () -> Unit): ProviderException {
        try {
            block()
        } catch (e: ProviderException) {
            return e
        }
        fail("expected ProviderException")
        throw AssertionError()
    }

    // ---------------------------------------------------------------- translation / chat

    @Test
    fun translationUsesHunyuanMtPromptModelAndBearerKey() = runBlocking {
        server.enqueue(completion(" Today we discuss the release plan. "))
        val out = HunyuanTranslationProvider(chat(), "hunyuan-translation").translate("今天讨论发布规划。", Lang.ZH, Lang.EN)
        assertEquals("Today we discuss the release plan.", out)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer hy-key", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"model\":\"hunyuan-translation\""))
        assertTrue(body, body.contains("把下面的文本翻译成英语，不要额外解释。"))
    }

    @Test
    fun nonChinesePairsUseTheEnglishInstruction() {
        val prompt = HunyuanPrompts.translation("Guten Morgen", Lang.DE, Lang.FR).single().content
        assertTrue(prompt.startsWith("Translate the following segment into French"))
    }

    @Test
    fun rejectedKeyIsAnAuthErrorForTheCloud() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""))
        val e = expectError { HunyuanTranslationProvider(chat(), "hunyuan-translation").translate("你好", Lang.ZH, Lang.EN) }
        assertEquals(ProviderErrorKind.AUTH, e.kind)
        assertEquals(ServiceKind.TRANSLATION, e.service)
        assertFalse(e.onPrem)
        assertTrue(e.fatal)
        assertTrue(e.detail, e.detail.contains("Invalid API key"))
    }

    @Test
    fun closedPortIsUnreachableForOnPrem() = runBlocking {
        val url = server.url("/v1").toString()
        server.shutdown()
        val client = HunyuanChatClient(url, { "t" }, 2, onPrem = true)
        val e = expectError { client.complete("Hunyuan-MT-7B", HunyuanPrompts.translation("你好", Lang.ZH, Lang.EN), ServiceKind.TRANSLATION) }
        assertEquals(ProviderErrorKind.UNREACHABLE, e.kind)
        assertTrue(e.onPrem)
    }

    @Test
    fun slowServerIsATimeout() = runBlocking {
        server.enqueue(completion("late").setHeadersDelay(3, TimeUnit.SECONDS))
        val e = expectError { HunyuanTranslationProvider(chat(timeoutSec = 1), "m").translate("你好", Lang.ZH, Lang.EN) }
        assertEquals(ProviderErrorKind.TIMEOUT, e.kind)
        assertEquals(1, e.timeoutSec)
    }

    // ---------------------------------------------------------------- minutes

    private fun seg(id: String, speaker: String, start: Long, text: String) =
        Segment(id, speaker, Channel.LOCAL, start, start + 4_000, text, Lang.ZH, isFinal = true)

    private val record = MeetingRecord(
        id = "m1",
        code = "ABCD23",
        title = "项目周会",
        mode = MeetingMode.IN_ROOM,
        startedAtEpochMs = 0,
        state = MeetingState.GENERATING,
        primaryLang = Lang.ZH,
        profileId = "hunyuan-cloud",
        templateId = "general",
        speakers = listOf(Speaker("S1", 1, Channel.LOCAL, 0), Speaker("S2", 2, Channel.LOCAL, 1, name = "王磊")),
        segments = listOf(
            seg("a", "S1", 0, "今天讨论第三季度的发布规划。"),
            seg("b", "S2", 5_000, "我们决定把发布日期定在十月二十号。"),
            seg("c", "S2", 10_000, "测试报告我来负责，周五前提交。"),
        ),
    )

    @Test
    fun minutesPromptNumbersTranscriptLinesAndListsTemplateCards() {
        val messages = HunyuanPrompts.minutes(MinutesRequest(record, "general", Lang.ZH))
        assertTrue(messages[0].content.contains("TOPICS") && messages[0].content.contains("TODOS"))
        assertTrue(messages[1].content, messages[1].content.contains("[2] 王磊 00:00:05 我们决定把发布日期定在十月二十号。"))
        assertTrue(messages[1].content.contains("[1] 发言人 1 00:00:00"))
    }

    @Test
    fun minutesReplyMapsRefsToSegmentsAndMarksUnanchoredItemsInferred() {
        val reply = """
            ```json
            {"overview":{"text":"讨论了发布规划","refs":[1]},
             "cards":{"TOPICS":[{"text":"第三季度发布规划","refs":[1]}],
                      "DECISIONS":[{"text":"发布日期十月二十号","refs":[2, 99]}],
                      "TODOS":[{"text":"提交测试报告","refs":[3],"owner":"王磊","due":"周五"},{"text":"同步市场部","refs":[]}],
                      "RISKS":[{"text":"不在模板里","refs":[1]}]}}
            ```
        """.trimIndent()
        val s = HunyuanPrompts.parseMinutes(reply, MinutesRequest(record, "general", Lang.ZH), nowMs = 42)
        assertEquals(listOf(CardType.TOPICS, CardType.DECISIONS, CardType.TODOS), s.cards.map { it.type })
        assertEquals(listOf("a"), s.overview.sourceRefs.map { it.segmentId })
        val decision = s.cards[1].items.single()
        assertEquals(listOf("b"), decision.sourceRefs.map { it.segmentId })
        assertEquals(5_000L, decision.sourceRefs.single().startMs)
        val todos = s.cards[2].items
        assertEquals("王磊", todos[0].owner)
        assertEquals("周五", todos[0].due)
        assertFalse(todos[0].inferred)
        assertTrue(todos[1].inferred)
        assertEquals(42L, s.generatedAtEpochMs)
    }

    @Test
    fun unparsableMinutesAreABadResponse() = runBlocking {
        server.enqueue(completion("抱歉，我无法完成。"))
        val provider = HunyuanMinutesProvider(chat(), "hunyuan-turbos-latest") { error("unused") }
        val e = expectError { provider.generate(MinutesRequest(record, "general", Lang.ZH)) {} }
        assertEquals(ProviderErrorKind.BAD_RESPONSE, e.kind)
        assertEquals(ServiceKind.SUMMARY, e.service)
    }

    @Test
    fun longTranscriptsAreTrimmedInTheMiddle() {
        val lines = (1..1000).map { "[$it] 发言人 1 00:00:00 " + "很长的一句话".repeat(10) }
        val fitted = HunyuanPrompts.fitTranscript(lines, maxChars = 5_000)
        assertTrue(fitted.length <= 5_100)
        assertTrue(fitted.startsWith("[1] "))
        assertTrue(fitted.endsWith(lines.last()))
        assertTrue(fitted.contains("中间省略"))
    }

    // ---------------------------------------------------------------- Tencent real-time ASR

    @Test
    fun asrSignatureMatchesTencentAlgorithm() {
        val params = TencentAsrSigner.params("AKIDtest", "16k_zh_large", "voice-1", 1_700_000_000, 12345)
        val signString = TencentAsrSigner.signString("asr.cloud.tencent.com", "1250000000", params)
        assertEquals(
            "asr.cloud.tencent.com/asr/v2/1250000000?convert_num_mode=1&engine_model_type=16k_zh_large&expired=1700086400" +
                "&needvad=1&nonce=12345&secretid=AKIDtest&timestamp=1700000000&voice_format=1&voice_id=voice-1&word_info=0",
            signString,
        )
        assertEquals("cjPNATjWLeSXkHvd7XM+2SCExjU=", TencentAsrSigner.sign("secretKeyTest", signString))
        val url = TencentAsrSigner.url("asr.cloud.tencent.com", "1250000000", "secretKeyTest", params)
        assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1250000000?"))
        assertTrue(url.endsWith("&signature=cjPNATjWLeSXkHvd7XM%2B2SCExjU%3D"))
    }

    @Test
    fun asrEnginePerLanguage() {
        assertEquals("16k_zh_large", TencentAsrSigner.engineFor(Lang.ZH, false, "16k_zh_large"))
        assertEquals("16k_ja", TencentAsrSigner.engineFor(Lang.JA, false, "16k_zh_large"))
        assertEquals("16k_zh_en", TencentAsrSigner.engineFor(Lang.ZH, true, "16k_zh_large"))
    }

    private fun asrConfig() = AsrSessionConfig("m1", Lang.ZH, false, setOf(Channel.LOCAL)) { 0 }
    private val creds = TencentAsrCredentials("1250000000", { "AKIDtest" }, { "secretKeyTest" })
    private fun wsUrl() = server.url("/asr").toString().replaceFirst("http", "ws")

    @Test
    fun asrResultsAreMappedOntoTheMeetingClock() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                var answered = false
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"code":0,"message":"success","voice_id":"v1"}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (answered) return
                    answered = true
                    webSocket.send("""{"code":0,"voice_id":"v1","result":{"slice_type":1,"index":0,"start_time":100,"end_time":800,"voice_text_str":"今天讨论"}}""")
                    webSocket.send("""{"code":0,"voice_id":"v1","result":{"slice_type":2,"index":0,"start_time":100,"end_time":1500,"voice_text_str":"今天讨论发布规划。"}}""")
                }
            }),
        )
        val session = TencentAsrSession(asrConfig(), creds, "16k_zh_large", spoolDir, 5, "asr.cloud.tencent.com") { wsUrl() }
        try {
            withTimeout(5_000) { session.link.first { it == LinkState.CONNECTED } }
            val events = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { session.events.take(2).toList() } }
            session.sendAudio(AudioFrame(Channel.LOCAL, 5_000, ShortArray(320)))
            session.sendAudio(AudioFrame(Channel.LOCAL, 5_020, ShortArray(320)))
            val (partial, final) = events.await()
            assertTrue(partial is AsrEvent.Partial)
            final as AsrEvent.Final
            assertEquals("今天讨论发布规划。", final.text)
            assertEquals(5_100L, final.startMs)
            assertEquals(6_500L, final.endMs)
            assertEquals("v1-0", final.segmentKey)
        } finally {
            session.close()
        }
    }

    @Test
    fun asrSignatureRejectionStopsRetryingAndExplains() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"code":4002,"message":"鉴权失败"}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }),
        )
        val session = TencentAsrSession(asrConfig(), creds, "16k_zh_large", spoolDir, 5, "asr.cloud.tencent.com") { wsUrl() }
        try {
            val e = withTimeout(5_000) { session.error.first { it != null } }!!
            assertEquals(ProviderErrorKind.AUTH, e.kind)
            assertEquals(ServiceKind.ASR, e.service)
            assertFalse(e.onPrem)
            assertEquals(LinkState.OFFLINE, session.link.value)
        } finally {
            session.close()
        }
    }

    @Test
    fun missingCloudCredentialsAreReportedNotRetried() {
        val session = TencentAsrSession(asrConfig(), TencentAsrCredentials("", { null }, { null }), "16k_zh_large", spoolDir, 5, "asr.cloud.tencent.com", null)
        assertEquals(ProviderErrorKind.NOT_CONFIGURED, session.error.value?.kind)
        assertEquals(LinkState.OFFLINE, session.link.value)
        session.close()
    }

    @Test
    fun onPremStreamRejectedTokenIsAnAuthError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = RemoteClient(server.url("/").toString(), 5) { "bad-token" }
        val session = RemoteAsrSession(client, asrConfig(), FrameSpool(java.io.File(spoolDir, "r.spool")), "/v1/asr/stream")
        try {
            val e = withTimeout(5_000) { session.error.first { it != null } }!!
            assertEquals(ProviderErrorKind.AUTH, e.kind)
            assertTrue(e.onPrem)
            assertEquals(LinkState.OFFLINE, session.link.value)
        } finally {
            session.close()
        }
    }

    // ---------------------------------------------------------------- factory / settings

    @Test
    fun factoryHonoursTheUsersChoice() = runBlocking {
        val factory = ProviderFactory(spoolDir) { _, _ -> null }
        assertEquals(BackendKind.DEMO, factory.create(ServerProfile("enterprise", ProfileKind.ENTERPRISE)).backend)
        val onPrem = factory.create(ServerProfile("enterprise", ProfileKind.ENTERPRISE, demoBackend = false))
        assertEquals(BackendKind.SCH900S_ON_PREM, onPrem.backend)
        val h1 = onPrem.gateway.health() as HealthResult.Unavailable
        assertEquals(ProviderErrorKind.NOT_CONFIGURED, h1.error?.kind)
        val cloud = factory.create(ServerProfile("hunyuan-cloud", ProfileKind.HUNYUAN_CLOUD, demoBackend = false))
        assertEquals(BackendKind.HUNYUAN_CLOUD, cloud.backend)
        val h2 = cloud.gateway.health() as HealthResult.Unavailable
        assertEquals(ProviderErrorKind.NOT_CONFIGURED, h2.error?.kind)
        assertFalse(h2.error!!.onPrem)
    }

    @Test
    fun onPremDefaultsDeriveFromTheGatewayAddress() {
        val p = ServerProfile("enterprise", ProfileKind.ENTERPRISE, baseUrl = "https://sch900s.corp.local:8443/")
        assertEquals("https://sch900s.corp.local:8443/v1", p.effectiveLlmBaseUrl)
        assertEquals("/v1/asr/stream", p.effectiveAsrPath)
        assertEquals("Hunyuan-MT-7B", p.effectiveTranslationModel)
        val c = ServerProfile("hunyuan-cloud", ProfileKind.HUNYUAN_CLOUD)
        assertEquals("https://api.hunyuan.cloud.tencent.com/v1", c.effectiveLlmBaseUrl)
        assertEquals("hunyuan-turbos-latest", c.effectiveSummaryModel)
    }

    @Test
    fun settingsSavedWithTheOldLenovoCloudProfileStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"profiles":[{"id":"enterprise","kind":"ENTERPRISE","baseUrl":"https://sch900s.corp.local"},
            {"id":"lenovo-cloud","kind":"LENOVO_CLOUD"}],"activeProfileId":"lenovo-cloud","captionBar":{"x":1,"y":2,"w":3,"h":4}}"""
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertEquals(ProfileKind.HUNYUAN_CLOUD, s.activeProfile.kind)
        assertEquals(3, s.captionBar.w)
        assertNotNull(s.profile(ProfileKind.ENTERPRISE))
    }
}
