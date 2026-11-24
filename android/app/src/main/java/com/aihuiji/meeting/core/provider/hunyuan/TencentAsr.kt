package com.aihuiji.meeting.core.provider.hunyuan

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.HunyuanDefaults
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrProvider
import com.aihuiji.meeting.core.provider.AsrSession
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.ProviderErrorKind
import com.aihuiji.meeting.core.provider.ProviderErrors
import com.aihuiji.meeting.core.provider.ProviderException
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.core.provider.ServiceKind
import com.aihuiji.meeting.core.provider.remote.FrameSpool
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Tencent Cloud real-time ASR credentials; read lazily so a key changed in settings applies to the next session. */
class TencentAsrCredentials(
    val appId: String,
    val secretId: () -> String?,
    val secretKey: () -> String?,
)

/**
 * Request signing for `wss://asr.cloud.tencent.com/asr/v2/<appid>` (Tencent Cloud 实时语音识别):
 * the sign string is host + path + `?` + every parameter sorted by key, signed with HMAC-SHA1 using
 * the SecretKey, Base64-encoded, then URL-encoded into `signature`.
 */
object TencentAsrSigner {
    fun signString(host: String, appId: String, params: Map<String, String>): String =
        "$host/asr/v2/$appId?" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    fun sign(secretKey: String, signString: String): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA1")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(signString.toByteArray()))
    }

    fun url(host: String, appId: String, secretKey: String, params: Map<String, String>): String {
        val s = signString(host, appId, params)
        return "wss://$s&signature=${URLEncoder.encode(sign(secretKey, s), "UTF-8")}"
    }

    fun params(secretId: String, engine: String, voiceId: String, nowSec: Long, nonce: Int): Map<String, String> = mapOf(
        "secretid" to secretId,
        "timestamp" to nowSec.toString(),
        "expired" to (nowSec + 24 * 3600).toString(),
        "nonce" to nonce.toString(),
        "engine_model_type" to engine,
        "voice_id" to voiceId,
        "voice_format" to "1",
        "needvad" to "1",
        "convert_num_mode" to "1",
        "word_info" to "0",
    )

    /** The configured engine is used for Chinese; other languages use Tencent's per-language 16 kHz engines. */
    fun engineFor(lang: Lang, autoDetect: Boolean, zhEngine: String): String = when {
        autoDetect -> "16k_zh_en"
        lang == Lang.ZH -> zhEngine
        else -> "16k_${lang.code}"
    }
}

@Serializable
data class TencentAsrResult(
    @SerialName("slice_type") val sliceType: Int = 0,
    val index: Int = 0,
    @SerialName("start_time") val startTime: Long = 0,
    @SerialName("end_time") val endTime: Long = 0,
    @SerialName("voice_text_str") val text: String = "",
    @SerialName("speaker_id") val speakerId: Int? = null,
)

@Serializable
data class TencentAsrMessage(
    val code: Int = 0,
    val message: String = "",
    @SerialName("voice_id") val voiceId: String = "",
    val result: TencentAsrResult? = null,
    val final: Int = 0,
)

object TencentAsrCodes {
    /** 4002 signature, 4003 AppID not enabled, 4004 free quota used up, 4005 account in arrears, 4006 concurrency. */
    fun toError(code: Int, message: String, endpoint: String): ProviderException {
        val kind = when (code) {
            4002, 4003 -> ProviderErrorKind.AUTH
            4004, 4005, 4006 -> ProviderErrorKind.QUOTA
            else -> ProviderErrorKind.SERVER
        }
        return ProviderException(kind, ServiceKind.ASR, endpoint, "$code $message", onPrem = false)
    }
}

class TencentAsrProvider(
    private val creds: TencentAsrCredentials,
    private val zhEngine: String,
    private val spoolDir: File,
    private val timeoutSec: Int,
    private val host: String = HunyuanDefaults.CLOUD_ASR_HOST,
    private val urlFor: ((String) -> String)? = null,
) : AsrProvider {
    override val location = ProviderLocation.REMOTE
    override suspend fun open(config: AsrSessionConfig): AsrSession =
        TencentAsrSession(config, creds, TencentAsrSigner.engineFor(config.primaryLang, config.autoDetect, zhEngine), spoolDir, timeoutSec, host, urlFor)
}

/**
 * One Tencent real-time ASR WebSocket per audio channel (room mic and, for video meetings, the far
 * end). Captions are mapped back to the meeting clock from the offset of the first frame each
 * connection carried. While a connection is down, frames are spooled and replayed on reconnect, as
 * the SCH-900S session does; rejected credentials or exhausted quota stop retries and surface [error].
 */
class TencentAsrSession(
    private val config: AsrSessionConfig,
    private val creds: TencentAsrCredentials,
    private val engine: String,
    spoolDir: File,
    timeoutSec: Int,
    private val host: String,
    private val urlFor: ((String) -> String)?,
) : AsrSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<AsrEvent> = _events.asSharedFlow()
    private val _link = MutableStateFlow(LinkState.CONNECTING)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _error = MutableStateFlow<ProviderException?>(null)
    override val error: StateFlow<ProviderException?> = _error.asStateFlow()

    @Volatile private var closed = false
    @Volatile private var paused = false
    private val streams = config.channels.associateWith { ch -> ChannelStream(ch, FrameSpool(File(spoolDir, "${config.meetingId}-${ch.name.lowercase()}.tc.spool"))) }

    init {
        val id = creds.secretId()
        val key = creds.secretKey()
        if (creds.appId.isBlank() || id.isNullOrBlank() || key.isNullOrBlank()) {
            fail(ProviderException(ProviderErrorKind.NOT_CONFIGURED, ServiceKind.ASR, host, "AppId / SecretId / SecretKey", onPrem = false))
        } else {
            streams.values.forEach { it.connect() }
        }
    }

    private fun fail(e: ProviderException) {
        _error.value = e
        if (e.fatal) {
            _link.value = LinkState.OFFLINE
            streams.values.forEach { it.stop() }
        }
    }

    private fun refreshLink() {
        if (_error.value?.fatal == true) return
        val states = streams.values.map { it.state }
        _link.value = when {
            states.any { it == LinkState.RECONNECTING } -> LinkState.RECONNECTING
            states.any { it == LinkState.CONNECTING } -> LinkState.CONNECTING
            states.any { it == LinkState.BACKFILLED } -> LinkState.BACKFILLED
            else -> LinkState.CONNECTED
        }
    }

    private inner class ChannelStream(val channel: Channel, val spool: FrameSpool) {
        @Volatile var state = LinkState.CONNECTING
        @Volatile private var socket: WebSocket? = null
        @Volatile private var stopped = false
        @Volatile private var replaying = false
        @Volatile private var baseOffset = -1L
        private var attempt = 0
        private val pending = ArrayList<Short>(1280)
        private var pendingOffset = 0L
        var finalAck: CompletableDeferred<Unit>? = null
        private val lock = Any()

        fun connect() {
            if (closed || stopped || paused) return
            val voiceId = UUID.randomUUID().toString()
            val params = TencentAsrSigner.params(creds.secretId().orEmpty(), engine, voiceId, System.currentTimeMillis() / 1000, Random.nextInt(1, 9_999_999))
            val url = urlFor?.invoke(voiceId) ?: TencentAsrSigner.url(host, creds.appId, creds.secretKey().orEmpty(), params)
            baseOffset = -1L
            http.newWebSocket(Request.Builder().url(url).build(), Listener())
        }

        fun stop() {
            stopped = true
            socket?.close(1000, null)
            socket = null
        }

        /** Tencent recommends 40 ms packets; capture delivers 20 ms frames, so two are joined. */
        fun offer(frame: AudioFrame) = synchronized(lock) {
            if (pending.isEmpty()) pendingOffset = frame.offsetMs
            frame.pcm.forEach { pending.add(it) }
            if (pending.size < 640) return@synchronized
            val packet = AudioFrame(channel, pendingOffset, pending.toShortArray())
            pending.clear()
            val ws = socket
            if (ws == null || replaying || !send(ws, packet)) spool.append(packet)
        }

        private fun send(ws: WebSocket, frame: AudioFrame): Boolean {
            if (baseOffset < 0) baseOffset = frame.offsetMs
            val buf = ByteBuffer.allocate(frame.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            frame.pcm.forEach { buf.putShort(it) }
            return ws.send(buf.array().toByteString())
        }

        /** Replays spooled audio at up to 4x real time so the server does not reject the burst. */
        private fun replay(ws: WebSocket) {
            replaying = true
            scope.launch {
                var replayed = 0
                while (true) {
                    val frames = mutableListOf<AudioFrame>()
                    synchronized(lock) { spool.drain { frames += it } }
                    if (frames.isEmpty()) break
                    for (f in frames) {
                        if (socket !== ws) { synchronized(lock) { spool.append(f) }; continue }
                        send(ws, f)
                        replayed++
                        delay(10)
                    }
                }
                replaying = false
                if (replayed > 0) {
                    state = LinkState.BACKFILLED
                    refreshLink()
                    delay(2_000)
                    if (state == LinkState.BACKFILLED) { state = LinkState.CONNECTED; refreshLink() }
                }
            }
        }

        fun end() {
            val ack = CompletableDeferred<Unit>().also { finalAck = it }
            val ws = socket
            if (ws == null) ack.complete(Unit) else ws.send("""{"type":"end"}""")
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                attempt = 0
                state = LinkState.CONNECTED
                if (_error.value?.fatal == false) _error.value = null
                refreshLink()
                if (!spool.isEmpty) replay(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { HunyuanChatClient.json.decodeFromString<TencentAsrMessage>(text) }.getOrNull() ?: return
                if (msg.code != 0) {
                    fail(TencentAsrCodes.toError(msg.code, msg.message, host))
                    webSocket.close(1000, null)
                    return
                }
                msg.result?.let { r ->
                    val base = baseOffset.coerceAtLeast(0)
                    val key = "${msg.voiceId}-${r.index}"
                    val spk = r.speakerId?.toString()
                    if (r.text.isNotBlank()) {
                        val event = if (r.sliceType == 2) {
                            AsrEvent.Final(key, channel, r.text, base + r.startTime, base + r.endTime, config.primaryLang, spk)
                        } else {
                            AsrEvent.Partial(key, channel, r.text, base + r.startTime, config.primaryLang, spk)
                        }
                        _events.tryEmit(event)
                    }
                }
                if (msg.final == 1) finalAck?.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket || socket == null) socket = null
                val e = if (response != null && response.code in setOf(401, 403)) {
                    ProviderErrors.forHttp(response.code, response.message, ServiceKind.ASR, host, onPrem = false)
                } else ProviderErrors.classify(t, ServiceKind.ASR, host, onPrem = false)
                fail(e)
                finalAck?.complete(Unit)
                if (!e.fatal) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                finalAck?.complete(Unit)
                if (!closed && !paused && !stopped) scheduleReconnect()
            }
        }

        private fun scheduleReconnect() {
            if (closed || stopped || paused) return
            state = LinkState.RECONNECTING
            refreshLink()
            val backoff = (1_000L shl attempt.coerceAtMost(3)).coerceAtMost(10_000L)
            attempt++
            scope.launch {
                delay(backoff)
                connect()
            }
        }

        /** Closes the current recognition so no idle-timeout (4008) fires while the meeting is paused. */
        fun suspendForPause() {
            socket?.let { it.send("""{"type":"end"}"""); it.close(1000, "paused") }
            socket = null
        }
    }

    override fun sendAudio(frame: AudioFrame) {
        if (paused || closed) return
        streams[frame.channel]?.offer(frame)
    }

    override fun pause() {
        paused = true
        streams.values.forEach { it.suspendForPause() }
    }

    override fun resume() {
        paused = false
        if (_error.value?.fatal != true) streams.values.forEach { it.connect() }
    }

    override suspend fun finish() {
        streams.values.forEach { it.end() }
        withTimeoutOrNull(5_000) { streams.values.forEach { it.finalAck?.await() } }
    }

    override fun close() {
        closed = true
        streams.values.forEach { it.stop() }
        if (_error.value?.fatal != true) _link.value = LinkState.OFFLINE
        scope.cancel()
    }
}

/** Opens a recognition session and waits for the handshake reply: proves AppId, SecretId and SecretKey. */
object TencentAsrProbe {
    suspend fun check(creds: TencentAsrCredentials, engine: String, timeoutSec: Int, host: String = HunyuanDefaults.CLOUD_ASR_HOST): ProviderException? {
        val id = creds.secretId()
        val key = creds.secretKey()
        if (creds.appId.isBlank() || id.isNullOrBlank() || key.isNullOrBlank()) {
            return ProviderException(ProviderErrorKind.NOT_CONFIGURED, ServiceKind.ASR, host, "AppId / SecretId / SecretKey", onPrem = false)
        }
        val http = OkHttpClient.Builder().connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        val params = TencentAsrSigner.params(id, engine, UUID.randomUUID().toString(), System.currentTimeMillis() / 1000, Random.nextInt(1, 9_999_999))
        val result = CompletableDeferred<ProviderException?>()
        val ws = http.newWebSocket(Request.Builder().url(TencentAsrSigner.url(host, creds.appId, key, params)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { HunyuanChatClient.json.decodeFromString<TencentAsrMessage>(text) }.getOrNull()
                result.complete(if (msg == null || msg.code == 0) null else TencentAsrCodes.toError(msg.code, msg.message, host))
                webSocket.send("""{"type":"end"}""")
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(ProviderErrors.classify(t, ServiceKind.ASR, host, onPrem = false, timeoutSec = timeoutSec))
            }
        })
        return withTimeoutOrNull(timeoutSec * 1000L) { result.await() }
            ?: ProviderException(ProviderErrorKind.TIMEOUT, ServiceKind.ASR, host, onPrem = false, timeoutSec = timeoutSec).also { ws.cancel() }
    }
}
