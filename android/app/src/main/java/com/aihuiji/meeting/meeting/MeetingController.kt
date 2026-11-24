package com.aihuiji.meeting.meeting

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.aihuiji.meeting.audio.AudioCaptureEngine
import com.aihuiji.meeting.audio.CaptureStart
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.Mark
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.MeetingCode
import com.aihuiji.meeting.core.model.MeetingConfig
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.MeetingStateMachine
import com.aihuiji.meeting.core.model.Note
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.core.model.TimeRange
import com.aihuiji.meeting.core.provider.AsrEvent
import com.aihuiji.meeting.core.provider.AsrSession
import com.aihuiji.meeting.core.provider.AsrSessionConfig
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.ProviderBundle
import com.aihuiji.meeting.core.provider.ProviderFactory
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.core.provider.demo.DemoAsrSession
import com.aihuiji.meeting.data.MeetingRepository
import com.aihuiji.meeting.data.SettingsStore
import com.aihuiji.meeting.service.CaptionBarService
import com.aihuiji.meeting.service.MeetingForegroundService
import com.aihuiji.meeting.service.ProjectionConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface MicStatus {
    data class Ok(val name: String, val external: Boolean, val aec: Boolean) : MicStatus
    data object NoPermission : MicStatus
    data object NoDevice : MicStatus
    data class Failed(val reason: String) : MicStatus
}

enum class FarStatus { NOT_NEEDED, ACTIVE, UNAVAILABLE }

data class LiveMeeting(
    val record: MeetingRecord,
    val config: MeetingConfig,
    val elapsedMs: Long = 0,
    val link: LinkState = LinkState.CONNECTING,
    val levels: Map<Channel, Float> = emptyMap(),
    val activeSpeakerId: String? = null,
    val mic: MicStatus? = null,
    val far: FarStatus = FarStatus.NOT_NEEDED,
    /** The caption bar is collapsed to the floating icon. */
    val barMinimized: Boolean = false,
    val translationEnabled: Boolean = config.translationEnabled,
    val targetLangs: List<Lang> = config.targetLangs,
    val location: ProviderLocation = ProviderLocation.DEMO,
) {
    val state: MeetingState get() = record.state
    val isPaused: Boolean get() = record.state == MeetingState.PAUSED
}

data class Generation(val meetingId: String, val progress: Float, val error: String? = null, val done: Boolean = false)

/**
 * Meeting orchestration (framework §6.1 会议编排层): lifecycle state machine, audio → ASR →
 * speaker → translation pipeline, speed notes, and post-meeting minutes. UI talks only to this.
 */
class MeetingController(
    private val context: Context,
    private val settings: SettingsStore,
    private val repository: MeetingRepository,
    private val providers: ProviderFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _live = MutableStateFlow<LiveMeeting?>(null)
    val live: StateFlow<LiveMeeting?> = _live.asStateFlow()

    private val _generation = MutableStateFlow<Generation?>(null)
    val generation: StateFlow<Generation?> = _generation.asStateFlow()

    private var bundle: ProviderBundle? = null
    private var asr: AsrSession? = null
    private var engine: AudioCaptureEngine? = null
    private var jobs = mutableListOf<Job>()
    private var startRealtime = 0L
    private var pausedAt = -1L
    private val speakerKeys = mutableMapOf<String, String>()
    private var lastSave = 0L

    fun elapsed(): Long = if (startRealtime == 0L) 0 else SystemClock.elapsedRealtime() - startRealtime

    fun bundleFor(profileId: String): ProviderBundle {
        val profile = settings.value.profiles.firstOrNull { it.id == profileId } ?: settings.value.activeProfile
        return providers.create(profile)
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(config: MeetingConfig, title: String, projectionConsent: ProjectionConsent?): MeetingRecord {
        check(_live.value == null) { "A meeting is already running" }
        val id = UUID.randomUUID().toString()
        val dir = repository.dirFor(id)
        val b = bundleFor(config.profileId).also { bundle = it }
        val wantsFar = config.mode == MeetingMode.VIDEO && projectionConsent != null
        var record = MeetingRecord(
            id = id,
            code = MeetingCode.generate(),
            title = title,
            mode = config.mode,
            startedAtEpochMs = System.currentTimeMillis(),
            state = MeetingState.PREPARING,
            primaryLang = config.primaryLang,
            autoDetect = config.autoDetect,
            targetLangs = config.targetLangs,
            dualChannel = wantsFar,
            profileId = config.profileId,
            templateId = config.templateId,
        )
        MeetingStateMachine.require(record.state, MeetingState.LIVE)
        record = record.copy(state = MeetingState.LIVE)
        speakerKeys.clear()
        startRealtime = SystemClock.elapsedRealtime()
        pausedAt = -1

        val audio = AudioCaptureEngine(context, ::elapsed).also { engine = it }
        val mic = when (val r = audio.startNear(dir)) {
            is CaptureStart.Started -> MicStatus.Ok(r.deviceName, r.external, r.aecActive)
            CaptureStart.NoPermission -> MicStatus.NoPermission
            CaptureStart.NoDevice -> MicStatus.NoDevice
            is CaptureStart.Failed -> MicStatus.Failed(r.reason)
        }
        _live.value = LiveMeeting(
            record = record,
            config = config,
            mic = mic,
            far = if (config.mode == MeetingMode.VIDEO) (if (wantsFar) FarStatus.ACTIVE else FarStatus.UNAVAILABLE) else FarStatus.NOT_NEEDED,
            location = b.location,
        )
        scope.launch { repository.save(record) }

        MeetingForegroundService.start(context, micGranted = mic !is MicStatus.NoPermission, consent = projectionConsent)
        CaptionBarService.show(context)

        val channels = if (wantsFar) setOf(Channel.LOCAL, Channel.REMOTE) else setOf(Channel.LOCAL)
        if (config.recordOnly) {
            _live.update { it?.copy(link = LinkState.OFFLINE) }
        } else jobs += scope.launch {
            val session = b.asr.open(AsrSessionConfig(id, config.primaryLang, config.autoDetect, channels, ::elapsed))
            asr = session
            audio.onFrame = { session.sendAudio(it) }
            launch { session.link.collect { l -> _live.update { it?.copy(link = l) } } }
            session.events.collect(::onAsrEvent)
        }
        jobs += scope.launch {
            audio.levels.collect { lv -> _live.update { it?.copy(levels = lv) } }
        }
        jobs += scope.launch {
            while (isActive) {
                _live.update { it?.copy(elapsedMs = elapsed()) }
                delay(250)
            }
        }
        return record
    }

    /** Called by [MeetingForegroundService] once the MediaProjection exists (after startForeground). */
    fun attachProjection(projection: MediaProjection) {
        val live = _live.value ?: return
        val ok = engine?.startFar(projection, repository.dirFor(live.record.id)) == true
        _live.update {
            it?.copy(far = if (ok) FarStatus.ACTIVE else FarStatus.UNAVAILABLE, record = it.record.copy(dualChannel = ok || it.location == ProviderLocation.DEMO))
        }
    }

    fun retryMic() {
        val live = _live.value ?: return
        val audio = engine ?: return
        if (Channel.LOCAL in audio.activeChannels) return
        val mic = when (val r = audio.startNear(repository.dirFor(live.record.id))) {
            is CaptureStart.Started -> MicStatus.Ok(r.deviceName, r.external, r.aecActive)
            CaptureStart.NoPermission -> MicStatus.NoPermission
            CaptureStart.NoDevice -> MicStatus.NoDevice
            is CaptureStart.Failed -> MicStatus.Failed(r.reason)
        }
        _live.update { it?.copy(mic = mic) }
    }

    fun pause() = transition(MeetingState.PAUSED) {
        pausedAt = elapsed()
        engine?.pause()
        asr?.pause()
        null
    }

    fun resume() = transition(MeetingState.LIVE) { rec ->
        engine?.resume()
        asr?.resume()
        val range = TimeRange(pausedAt, elapsed())
        pausedAt = -1
        rec.copy(pausedRanges = rec.pausedRanges + range)
    }

    private fun transition(to: MeetingState, effect: (MeetingRecord) -> MeetingRecord? = { null }) {
        val live = _live.value ?: return
        if (!MeetingStateMachine.canTransition(live.state, to)) return
        val next = (effect(live.record) ?: live.record).copy(state = to)
        _live.update { it?.copy(record = next) }
    }

    fun setBarMinimized(minimized: Boolean) {
        _live.update { it?.copy(barMinimized = minimized) }
    }

    fun setTranslation(enabled: Boolean, targets: List<Lang>) {
        val live = _live.value ?: return
        _live.update { it?.copy(translationEnabled = enabled, targetLangs = targets, record = it.record.copy(targetLangs = targets)) }
        if (enabled) live.record.finalSegments.takeLast(4).forEach(::translate)
    }

    /** Demo backend only: drops the stream for a few seconds to show 重连中 → 已补齐. */
    fun simulateOutage() {
        (asr as? DemoAsrSession)?.simulateOutage(8_000)
    }

    /**
     * 结束 → 设备复位 → 总结. Stops capture, flushes trailing ASR, clears the live session (F-29),
     * then generates minutes in the background. Returns the meeting id for navigation.
     */
    fun end(): String? {
        val live = _live.value ?: return null
        if (!MeetingStateMachine.canTransition(live.state, MeetingState.ENDING)) return null
        val ending = live.record.copy(
            state = MeetingState.ENDING,
            pausedRanges = if (live.isPaused) live.record.pausedRanges + TimeRange(pausedAt, elapsed()) else live.record.pausedRanges,
        )
        _generation.value = Generation(ending.id, 0f)
        _live.update { it?.copy(record = ending) }
        CaptionBarService.hide(context)
        scope.launch {
            asr?.runCatching { finish() }
            val files = engine?.stop().orEmpty()
            asr?.close()
            jobs.forEach { it.cancel() }
            jobs.clear()
            CaptionBarService.hide(context)
            MeetingForegroundService.stop(context)
            val latest = _live.value?.record ?: ending
            val finished = latest.copy(
                durationMs = elapsed(),
                segments = latest.segments.filter { it.isFinal },
                audioFiles = files.mapValues { it.value.absolutePath },
                state = MeetingState.GENERATING,
            )
            reset()
            if (live.config.recordOnly) {
                // Audio is kept; minutes are produced once the server is back (重试生成).
                repository.save(finished.copy(state = MeetingState.FAILED, failureReason = RECORD_ONLY))
                _generation.value = Generation(finished.id, 0f, error = RECORD_ONLY)
            } else {
                repository.save(finished)
                generate(finished.id, finished.templateId)
            }
        }
        return ending.id
    }

    private fun reset() {
        _live.value = null
        asr = null
        engine = null
        startRealtime = 0
        speakerKeys.clear()
    }

    /** Recover a meeting interrupted by a crash or power loss: keep what was saved and summarise it. */
    fun recover(id: String) {
        val rec = repository.get(id) ?: return
        scope.launch {
            val durationMs = rec.durationMs.takeIf { it > 0 } ?: (rec.segments.maxOfOrNull { it.endMs } ?: 0)
            repository.save(rec.copy(state = MeetingState.GENERATING, durationMs = durationMs, segments = rec.finalSegments))
            generate(id, rec.templateId)
        }
    }

    fun regenerate(id: String, templateId: String) {
        scope.launch { generate(id, templateId) }
    }

    private suspend fun generate(id: String, templateId: String) {
        val rec = repository.get(id) ?: return
        _generation.value = Generation(id, 0f)
        val b = bundleFor(rec.profileId)
        try {
            if (rec.state != MeetingState.GENERATING) repository.save(rec.copy(state = MeetingState.GENERATING))
            val summary = b.minutes.generate(MinutesRequest(rec, templateId, rec.primaryLang)) { p ->
                _generation.value = Generation(id, p)
            }
            val share = rec.share ?: runCatching { b.minutes.createShareLink(rec) }.getOrNull()
            val history = listOfNotNull(rec.summary) + rec.previousSummaries
            repository.save(
                rec.copy(
                    state = MeetingState.DONE,
                    summary = summary,
                    templateId = templateId,
                    previousSummaries = history.take(5),
                    share = share,
                    failureReason = null,
                ),
            )
            _generation.value = Generation(id, 1f, done = true)
        } catch (e: Exception) {
            repository.save(rec.copy(state = MeetingState.FAILED, failureReason = e.message ?: e.javaClass.simpleName))
            _generation.value = Generation(id, 0f, error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- speed notes (F-23 / F-12)

    fun addMark(tag: MarkTag, segmentId: String? = null): Mark? {
        val live = _live.value ?: return null
        val at = segmentId?.let { sid -> live.record.segments.firstOrNull { it.id == sid }?.startMs } ?: elapsed()
        val mark = Mark(UUID.randomUUID().toString(), at, tag, segmentId)
        _live.update { it?.copy(record = it.record.copy(marks = it.record.marks + mark)) }
        return mark
    }

    fun retagMark(id: String, tag: MarkTag) {
        _live.update { it?.copy(record = it.record.copy(marks = it.record.marks.map { m -> if (m.id == id) m.copy(tag = tag) else m })) }
    }

    fun liveShareUrl(record: MeetingRecord): String {
        record.share?.let { return it.url }
        val profile = settings.value.profiles.firstOrNull { it.id == record.profileId } ?: settings.value.activeProfile
        val host = profile.baseUrl.trimEnd('/').ifBlank { "https://aihuiji.corp.local" }
        return "$host/m/${record.code}"
    }

    fun excerpt(segmentId: String, tag: MarkTag? = null) {
        val live = _live.value ?: return
        val seg = live.record.segments.firstOrNull { it.id == segmentId } ?: return
        val note = Note(UUID.randomUUID().toString(), seg.startMs, seg.text, seg.id, seg.speakerId, tag)
        _live.update { it?.copy(record = it.record.copy(notes = it.record.notes + note)) }
        if (tag != null) addMark(tag, segmentId)
    }

    fun addNote(text: String, tag: MarkTag? = null) {
        if (text.isBlank()) return
        val note = Note(UUID.randomUUID().toString(), elapsed(), text.trim(), tag = tag)
        _live.update { it?.copy(record = it.record.copy(notes = it.record.notes + note)) }
    }

    fun removeNote(id: String) {
        _live.update { it?.copy(record = it.record.copy(notes = it.record.notes.filterNot { n -> n.id == id })) }
    }

    // ---------------------------------------------------------------- ASR pipeline

    private fun onAsrEvent(event: AsrEvent) {
        val live = _live.value ?: return
        if (live.isPaused && event is AsrEvent.Partial) return
        when (event) {
            is AsrEvent.Partial -> upsert(event.segmentKey, event.channel, event.diarizationLabel, event.startMs, event.startMs, event.text, event.lang, false)
            is AsrEvent.Final -> {
                upsert(event.segmentKey, event.channel, event.diarizationLabel, event.startMs, event.endMs, event.text, event.lang, true)
                _live.value?.record?.segments?.firstOrNull { it.id == event.segmentKey }?.let(::translate)
                persistThrottled()
                scope.launch {
                    delay(1_500)
                    _live.update { l -> if (l?.activeSpeakerId != null && l.record.segments.lastOrNull()?.isFinal == true) l.copy(activeSpeakerId = null) else l }
                }
            }
        }
    }

    private fun upsert(key: String, channel: Channel, label: String?, start: Long, end: Long, text: String, lang: Lang, final: Boolean) {
        val speakerId = speakerFor(channel, label, start, end)
        _live.update { l ->
            l ?: return@update null
            val segs = l.record.segments
            val idx = segs.indexOfFirst { it.id == key }
            val seg = Segment(key, speakerId, channel, start, end, text, lang, final, if (idx >= 0) segs[idx].translations else emptyMap())
            val next = if (idx >= 0) segs.toMutableList().also { it[idx] = seg } else segs + seg
            l.copy(record = l.record.copy(segments = next), activeSpeakerId = speakerId)
        }
    }

    private fun speakerFor(channel: Channel, label: String?, start: Long, end: Long): String {
        val key = "${channel.name}:${label ?: "U"}"
        speakerKeys[key]?.let { return it }
        val live = _live.value ?: return key
        val ordinal = live.record.speakers.size + 1
        val speaker = Speaker(id = key, ordinal = ordinal, channel = channel, colorIndex = (ordinal - 1) % 8)
        speakerKeys[key] = speaker.id
        _live.update { it?.copy(record = it.record.copy(speakers = it.record.speakers + speaker)) }
        val b = bundle ?: return speaker.id
        scope.launch {
            val identity = runCatching { b.speaker.resolve(live.record.id, channel, label, start, end) }.getOrNull()
            val name = identity?.displayName ?: return@launch
            _live.update { l ->
                l?.copy(record = l.record.copy(speakers = l.record.speakers.map { if (it.id == speaker.id) it.copy(name = name) else it }))
            }
        }
        return speaker.id
    }

    private fun translate(seg: Segment) {
        val live = _live.value ?: return
        if (!live.translationEnabled) return
        val b = bundle ?: return
        live.targetLangs.filter { it != seg.lang && it !in seg.translations }.forEach { target ->
            scope.launch {
                val text = runCatching { b.translation.translate(seg.text, seg.lang, target) }.getOrNull() ?: return@launch
                _live.update { l ->
                    l?.copy(record = l.record.copy(segments = l.record.segments.map {
                        if (it.id == seg.id) it.copy(translations = it.translations + (target to text)) else it
                    }))
                }
            }
        }
    }

    companion object {
        const val RECORD_ONLY = "record_only"
    }

    private fun persistThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave < 3_000) return
        lastSave = now
        val rec = _live.value?.record ?: return
        scope.launch { repository.save(rec.copy(durationMs = elapsed())) }
    }
}
