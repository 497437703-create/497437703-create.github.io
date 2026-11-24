package com.aihuiji.meeting

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.core.provider.remote.FrameSpool
import com.aihuiji.meeting.core.provider.remote.RemoteAsrSession
import com.aihuiji.meeting.core.provider.remote.RemoteClient
import com.aihuiji.meeting.core.provider.remote.RemoteGateway
import com.aihuiji.meeting.core.provider.remote.RemoteSpeakerProvider
import com.aihuiji.meeting.core.provider.remote.RemoteTranslationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteProvidersTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RemoteClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = RemoteClient(server.url("/").toString()) { "secret-token" }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun healthMapsCapabilitiesAndSendsBearerToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"ok","modelVersion":"sch-asr-2.1","asrLangs":["zh","en","ja","xx"],
                   "translationPairs":{"zh":["en","fr"]},"templates":["general"],"speakerRecognition":true,"extra":1}""",
            ),
        )
        val h = RemoteGateway(client).health()
        assertTrue(h is HealthResult.Ok)
        val caps = (h as HealthResult.Ok).capabilities
        assertEquals(setOf(Lang.ZH, Lang.EN, Lang.JA), caps.asrLangs)
        assertEquals(setOf(Lang.EN, Lang.FR), caps.translationTargets[Lang.ZH])
        assertEquals("sch-asr-2.1", caps.modelVersion)
        val req = server.takeRequest()
        assertEquals("/v1/health", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
    }

    @Test
    fun healthReportsServerErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(RemoteGateway(client).health() is HealthResult.Unavailable)
    }

    @Test
    fun translatePostsContract() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"Hello"}"""))
        assertEquals("Hello", RemoteTranslationProvider(client).translate("你好", Lang.ZH, Lang.EN))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"from\":\"zh\"") && body.contains("\"to\":\"en\""))
    }

    @Test
    fun speakerFallsBackToDiarizationLabel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val id = RemoteSpeakerProvider(client).resolve("m1", Channel.REMOTE, "spk2", 0, 1000)
        assertEquals("REMOTE:spk2", id.clusterKey)
        assertEquals(null, id.displayName)
    }

    @Test
    fun audioFrameWireFormat() {
        val bytes = RemoteAsrSession.encode(AudioFrame(Channel.REMOTE, 1234L, shortArrayOf(1, -2))).toByteArray()
        assertEquals(1 + 8 + 4, bytes.size)
        assertEquals(1.toByte(), bytes[0])
        assertEquals(1234L, ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.BIG_ENDIAN).long)
        val pcm = ByteBuffer.wrap(bytes, 9, 4).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.toShort(), pcm.short)
        assertEquals((-2).toShort(), pcm.short)
    }

    @Test
    fun spoolReplaysFramesInOrderThenEmpties() {
        val file = File.createTempFile("spool", ".bin").apply { delete() }
        val spool = FrameSpool(file)
        repeat(5) { i -> spool.append(AudioFrame(if (i % 2 == 0) Channel.LOCAL else Channel.REMOTE, i * 20L, ShortArray(320) { i.toShort() })) }
        val out = mutableListOf<AudioFrame>()
        assertEquals(5, spool.drain { out += it })
        assertEquals(listOf(0L, 20L, 40L, 60L, 80L), out.map { it.offsetMs })
        assertEquals(Channel.REMOTE, out[1].channel)
        assertArrayEquals(ShortArray(320) { 3 }, out[3].pcm)
        assertTrue(spool.isEmpty)
        assertEquals(0, spool.drain { })
    }
}
