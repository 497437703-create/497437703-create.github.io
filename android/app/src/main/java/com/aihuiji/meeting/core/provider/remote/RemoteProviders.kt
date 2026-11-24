package com.aihuiji.meeting.core.provider.remote

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.ShareLink
import com.aihuiji.meeting.core.model.Summary
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrProvider
import com.aihuiji.meeting.core.provider.AsrSession
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.core.provider.MinutesProvider
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.ProviderBundle
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.core.provider.ServerCapabilities
import com.aihuiji.meeting.core.provider.ServerGateway
import com.aihuiji.meeting.core.provider.SpeakerIdentity
import com.aihuiji.meeting.core.provider.SpeakerProvider
import com.aihuiji.meeting.core.provider.TranslationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** HTTP/WebSocket client for one server profile; see [RemoteContract] for the provisional wire format. */
class RemoteClient(baseUrl: String, private val tokenProvider: () -> String?) {
    val base: String = baseUrl.trimEnd('/')
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun request(path: String): Request.Builder = Request.Builder().url(base + path).apply {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    fun wsUrl(path: String): String = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + path
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + path
        else -> base + path
    }

    suspend inline fun <reified T> get(path: String): T = execute(request(path).get().build())

    suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        execute(request(path).post(RemoteContract.json.encodeToString(body).toRequestBody(jsonType())).build())

    fun jsonType() = jsonType

    suspend inline fun <reified T> execute(req: Request): T = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp: Response ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${req.url.encodedPath}")
            RemoteContract.json.decodeFromString<T>(resp.body?.string().orEmpty())
        }
    }
}

object RemoteBackend {
    fun bundle(baseUrl: String, tokenProvider: () -> String?, spoolDir: File): ProviderBundle {
        val client = RemoteClient(baseUrl, tokenProvider)
        return ProviderBundle(
            location = ProviderLocation.REMOTE,
            gateway = RemoteGateway(client),
            asr = RemoteAsrProvider(client, spoolDir),
            translation = RemoteTranslationProvider(client),
            speaker = RemoteSpeakerProvider(client),
            minutes = RemoteMinutesProvider(client),
        )
    }
}

class RemoteGateway(private val client: RemoteClient) : ServerGateway {
    override suspend fun health(): HealthResult = try {
        val start = System.nanoTime()
        val dto = client.get<HealthDto>("/v1/health")
        val latency = (System.nanoTime() - start) / 1_000_000
        if (dto.status != "ok") {
            HealthResult.Unavailable(dto.status)
        } else {
            HealthResult.Ok(
                latencyMs = latency,
                capabilities = ServerCapabilities(
                    asrLangs = dto.asrLangs.mapNotNull(Lang::fromCode).toSet(),
                    translationTargets = dto.translationPairs.mapNotNull { (k, v) ->
                        Lang.fromCode(k)?.let { it to v.mapNotNull(Lang::fromCode).toSet() }
                    }.toMap(),
                    templateIds = dto.templates.toSet(),
                    speakerRecognition = dto.speakerRecognition,
                    modelVersion = dto.modelVersion,
                ),
            )
        }
    } catch (e: Exception) {
        HealthResult.Unavailable(e.message ?: e.javaClass.simpleName)
    }
}

class RemoteAsrProvider(private val client: RemoteClient, private val spoolDir: File) : AsrProvider {
    override val location = ProviderLocation.REMOTE
    override suspend fun open(config: AsrSessionConfig): AsrSession =
        RemoteAsrSession(client, config, FrameSpool(File(spoolDir, "${config.meetingId}.spool")))
}

/**
 * Streams PCM over a WebSocket. While disconnected, frames go to [FrameSpool]; on reconnect the
 * spool is replayed first so the server can backfill captions at their original offsets.
 */
class RemoteAsrSession(
    private val client: RemoteClient,
    private val config: AsrSessionConfig,
    private val spool: FrameSpool,
) : AsrSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<AsrEvent> = _events.asSharedFlow()
    private val _link = MutableStateFlow(LinkState.CONNECTING)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var paused = false
    private var attempt = 0
    private var closedAck: CompletableDeferred<Unit>? = null

    init { connect() }

    private fun connect() {
        if (closed) return
        val channels = config.channels.joinToString(",") { it.name.lowercase() }
        val path = "/v1/asr/stream?meeting=${config.meetingId}&lang=${config.primaryLang.code}" +
            "&auto=${config.autoDetect}&channels=$channels"
        val req = client.request("").url(client.wsUrl(path)).build()
        client.http.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            attempt = 0
            val replayed = spool.drain { webSocket.send(encode(it)) }
            if (paused) webSocket.send(control("pause"))
            _link.value = if (replayed > 0) LinkState.BACKFILLED else LinkState.CONNECTED
            if (replayed > 0) scope.launch {
                delay(2_000)
                if (_link.value == LinkState.BACKFILLED) _link.value = LinkState.CONNECTED
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val dto = runCatching { RemoteContract.json.decodeFromString<AsrEventDto>(text) }.getOrNull() ?: return
            val channel = if (dto.ch == "remote") Channel.REMOTE else Channel.LOCAL
            val lang = Lang.fromCode(dto.lang) ?: config.primaryLang
            when (dto.type) {
                "partial" -> _events.tryEmit(AsrEvent.Partial(dto.seg, channel, dto.text, dto.start, lang, dto.spk))
                "final" -> _events.tryEmit(AsrEvent.Final(dto.seg, channel, dto.text, dto.start, dto.end, lang, dto.spk))
                "closed" -> closedAck?.complete(Unit)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closedAck?.complete(Unit)
            if (!closed) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        socket = null
        if (closed) return
        _link.value = LinkState.RECONNECTING
        val backoff = (1_000L shl attempt.coerceAtMost(3)).coerceAtMost(10_000L)
        attempt++
        scope.launch {
            delay(backoff)
            connect()
        }
    }

    private fun control(type: String) = RemoteContract.json.encodeToString(ControlDto(type))

    override fun sendAudio(frame: AudioFrame) {
        if (paused || closed) return
        val ws = socket
        if (ws == null || !ws.send(encode(frame))) spool.append(frame)
    }

    override fun pause() {
        paused = true
        socket?.send(control("pause"))
    }

    override fun resume() {
        paused = false
        socket?.send(control("resume"))
    }

    override suspend fun finish() {
        val ack = CompletableDeferred<Unit>().also { closedAck = it }
        socket?.send(control("end"))
        withTimeoutOrNull(5_000) { ack.await() }
    }

    override fun close() {
        closed = true
        socket?.close(1000, "meeting ended")
        socket = null
        _link.value = LinkState.OFFLINE
        scope.cancel()
    }

    companion object {
        fun encode(frame: AudioFrame): ByteString {
            val buf = ByteBuffer.allocate(1 + 8 + frame.pcm.size * 2)
            buf.put(if (frame.channel == Channel.REMOTE) RemoteContract.CHANNEL_REMOTE else RemoteContract.CHANNEL_LOCAL)
            buf.order(ByteOrder.BIG_ENDIAN).putLong(frame.offsetMs)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            frame.pcm.forEach { buf.putShort(it) }
            return buf.array().toByteString()
        }
    }
}

class RemoteTranslationProvider(private val client: RemoteClient) : TranslationProvider {
    override suspend fun translate(text: String, from: Lang, to: Lang): String =
        client.post<TranslateRequestDto, TranslateResponseDto>("/v1/translate", TranslateRequestDto(text, from.code, to.code)).text
}

class RemoteSpeakerProvider(private val client: RemoteClient) : SpeakerProvider {
    override val location = ProviderLocation.REMOTE
    override suspend fun resolve(meetingId: String, channel: Channel, diarizationLabel: String?, startMs: Long, endMs: Long): SpeakerIdentity =
        try {
            val dto = client.post<SpeakerResolveRequestDto, SpeakerResolveResponseDto>(
                "/v1/speakers/resolve",
                SpeakerResolveRequestDto(meetingId, channel.name.lowercase(), diarizationLabel, startMs, endMs),
            )
            SpeakerIdentity(dto.clusterKey, dto.name, dto.confidence)
        } catch (_: Exception) {
            // Fall back to the stream's own diarization label so captions never block on voiceprint.
            SpeakerIdentity("${channel.name}:${diarizationLabel ?: "U"}", null, 0f)
        }
}

class RemoteMinutesProvider(private val client: RemoteClient) : MinutesProvider {
    override suspend fun generate(request: MinutesRequest, onProgress: (Float) -> Unit): Summary {
        val body = MinutesJobRequestDto(
            meetingId = request.record.id,
            templateId = request.templateId,
            outputLang = request.outputLang.code,
            record = RemoteContract.json.encodeToJsonElement(request.record.copy(audioFiles = emptyMap())),
        )
        var job = client.post<MinutesJobRequestDto, MinutesJobDto>("/v1/minutes/jobs", body)
        while (true) {
            onProgress(job.progress)
            when (job.status) {
                "done" -> return job.summary ?: throw IOException("minutes job ${job.jobId} returned no summary")
                "failed" -> throw IOException(job.error ?: "minutes job ${job.jobId} failed")
            }
            delay(1_500)
            job = client.get("/v1/minutes/jobs/${job.jobId}")
        }
    }

    override suspend fun createShareLink(record: MeetingRecord): ShareLink {
        val dto = client.post<ShareRequestDto, ShareResponseDto>("/v1/meetings/${record.id}/share", ShareRequestDto(record.code))
        return ShareLink(dto.url, record.code, dto.expiresAtEpochMs)
    }
}
