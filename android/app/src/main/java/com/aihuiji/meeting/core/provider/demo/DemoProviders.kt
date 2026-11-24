package com.aihuiji.meeting.core.provider.demo

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.ShareLink
import com.aihuiji.meeting.core.model.Summary
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrProvider
import com.aihuiji.meeting.core.provider.AsrSession
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.AudioFrame
import com.aihuiji.meeting.core.provider.BackendKind
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
import com.aihuiji.meeting.core.provider.minutes.HeuristicMinutesEngine
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/** Offline backend for demos, CI and UI work without SCH-900S or Hunyuan cloud keys. */
object DemoBackend {
    fun bundle(shareHost: String): ProviderBundle = ProviderBundle(
        location = ProviderLocation.DEMO,
        backend = BackendKind.DEMO,
        gateway = DemoGateway,
        asr = DemoAsrProvider(),
        translation = DemoTranslationProvider(),
        speaker = DemoSpeakerProvider,
        minutes = DemoMinutesProvider(shareHost),
    )

    val capabilities = ServerCapabilities(
        asrLangs = Lang.entries.toSet(),
        translationTargets = Lang.entries.associateWith { from -> Lang.entries.toSet() - from },
        templateIds = Templates.all.map { it.id }.toSet(),
        speakerRecognition = true,
        modelVersion = "demo-1",
    )
}

object DemoGateway : ServerGateway {
    override suspend fun health(): HealthResult {
        delay(150)
        return HealthResult.Ok(latencyMs = 38, capabilities = DemoBackend.capabilities)
    }
}

class DemoAsrProvider(
    private val unitDelayMs: Long = 110,
    private val gapMs: Long = 1_200,
) : AsrProvider {
    override val location = ProviderLocation.DEMO
    override suspend fun open(config: AsrSessionConfig): AsrSession = DemoAsrSession(config, unitDelayMs, gapMs)
}

/**
 * Plays [DemoScript] as a streaming recogniser: growing partials, then a final per line.
 * [simulateOutage] mimics a network drop — finals produced meanwhile are delivered on reconnect,
 * exercising the same backfill UI as the remote session's spool replay.
 */
class DemoAsrSession(
    private val config: AsrSessionConfig,
    private val unitDelayMs: Long,
    private val gapMs: Long,
) : AsrSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<AsrEvent> = _events.asSharedFlow()
    private val _link = MutableStateFlow(LinkState.CONNECTING)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var outageUntil = 0L
    private val held = ArrayDeque<AsrEvent.Final>()
    private val dual = Channel.REMOTE in config.channels
    private val lang = config.primaryLang

    init {
        scope.launch {
            delay(400)
            _link.value = LinkState.CONNECTED
            var index = 0
            while (isActive) {
                val line = DemoScript.lines[index % DemoScript.lines.size]
                playLine(line, index)
                index++
                val pause = if (index % DemoScript.lines.size == 0) gapMs * 5 else gapMs
                waitWhilePaused(pause)
            }
        }
    }

    private suspend fun waitWhilePaused(ms: Long) {
        var left = ms
        while (left > 0) {
            delay(50)
            if (!paused) left -= 50
            checkOutageOver()
        }
    }

    private suspend fun playLine(line: DemoScript.Line, index: Int) {
        val text = line.text.getValue(lang)
        val units = if (lang.isCjk) text.map { it.toString() } else text.split(" ").map { "$it " }
        val channel = if (dual && line.speaker.startsWith("R")) Channel.REMOTE else Channel.LOCAL
        val key = "demo-$index-${UUID.randomUUID().toString().take(6)}"
        val start = config.meetingClockMs()
        val sb = StringBuilder()
        for ((i, u) in units.withIndex()) {
            waitWhilePaused(unitDelayMs + Random.nextLong(-20, 40))
            sb.append(u)
            if (i % 2 == 1 && !isOut()) {
                _events.emit(AsrEvent.Partial(key, channel, sb.toString().trimEnd(), start, lang, line.speaker))
            }
        }
        val final = AsrEvent.Final(key, channel, text, start, config.meetingClockMs(), lang, line.speaker)
        if (isOut()) synchronized(held) { held.addLast(final) } else _events.emit(final)
    }

    private fun isOut() = System.currentTimeMillis() < outageUntil

    private suspend fun releaseHeld() {
        val drained = synchronized(held) { held.toList().also { held.clear() } }
        drained.forEach { _events.emit(it) }
    }

    private suspend fun checkOutageOver() {
        if (outageUntil != 0L && !isOut()) {
            outageUntil = 0L
            releaseHeld()
            _link.value = LinkState.BACKFILLED
            scope.launch {
                delay(2_000)
                if (_link.value == LinkState.BACKFILLED) _link.value = LinkState.CONNECTED
            }
        }
    }

    fun simulateOutage(durationMs: Long) {
        outageUntil = System.currentTimeMillis() + durationMs
        _link.value = LinkState.RECONNECTING
    }

    override fun sendAudio(frame: AudioFrame) = Unit
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override suspend fun finish() {
        outageUntil = 0L
        releaseHeld()
        delay(200)
    }
    override fun close() {
        _link.value = LinkState.OFFLINE
        scope.cancel()
    }
}

class DemoTranslationProvider(private val latencyMs: Long = 500) : TranslationProvider {
    override suspend fun translate(text: String, from: Lang, to: Lang): String {
        delay(latencyMs)
        return DemoScript.find(text)?.text?.get(to) ?: "[${to.tag}] $text"
    }
}

object DemoSpeakerProvider : SpeakerProvider {
    override val location = ProviderLocation.DEMO
    override suspend fun resolve(meetingId: String, channel: Channel, diarizationLabel: String?, startMs: Long, endMs: Long) =
        SpeakerIdentity(clusterKey = "${channel.name}:${diarizationLabel ?: "U"}", displayName = null, confidence = 0.9f)
}

class DemoMinutesProvider(private val shareHost: String) : MinutesProvider {
    override suspend fun generate(request: MinutesRequest, onProgress: (Float) -> Unit): Summary {
        val steps = 24
        for (i in 1..steps) {
            delay(110)
            onProgress(i / steps.toFloat() * 0.95f)
        }
        val summary = HeuristicMinutesEngine.summarize(request) { text -> DemoScript.find(text)?.tags.orEmpty() }
        onProgress(1f)
        return summary
    }

    override suspend fun createShareLink(record: MeetingRecord): ShareLink {
        delay(120)
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val expires = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        return ShareLink(url = "https://$shareHost/s/${record.code}?t=$token", code = record.code, expiresAtEpochMs = expires)
    }
}
