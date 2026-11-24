package com.aihuiji.meeting.core.provider

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.ShareLink
import com.aihuiji.meeting.core.model.Summary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 16 kHz mono PCM16, 20 ms per frame unless the server capability list says otherwise. */
class AudioFrame(val channel: Channel, val offsetMs: Long, val pcm: ShortArray)

enum class ProviderLocation { REMOTE, LOCAL, DEMO }

/** The two ways an end user can have Tencent Hunyuan ASR, translation and minutes served, plus the offline demo. */
enum class BackendKind { HUNYUAN_CLOUD, SCH900S_ON_PREM, DEMO }

data class AsrSessionConfig(
    val meetingId: String,
    val primaryLang: Lang,
    val autoDetect: Boolean,
    val channels: Set<Channel>,
    /** Meeting-relative clock; providers that do not derive time from audio offsets use it. */
    val meetingClockMs: () -> Long,
)

sealed interface AsrEvent {
    /** A streaming hypothesis; [segmentKey] stays stable until the matching [Final]. */
    data class Partial(
        val segmentKey: String,
        val channel: Channel,
        val text: String,
        val startMs: Long,
        val lang: Lang,
        val diarizationLabel: String?,
    ) : AsrEvent

    data class Final(
        val segmentKey: String,
        val channel: Channel,
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val lang: Lang,
        val diarizationLabel: String?,
    ) : AsrEvent
}

interface AsrSession {
    val events: Flow<AsrEvent>
    val link: StateFlow<LinkState>
    /** Last backend problem worth showing (unreachable, rejected credentials, timeout); null once it recovers. */
    val error: StateFlow<ProviderException?> get() = NoAsrError
    fun sendAudio(frame: AudioFrame)
    fun pause()
    fun resume()
    /** Flushes buffered audio and waits for trailing finals before closing. */
    suspend fun finish()
    fun close()
}

private val NoAsrError: StateFlow<ProviderException?> = MutableStateFlow(null)

/** `AsrProvider (Remote | Local*)`: G1 is remote-only; G2 adds a local implementation. */
interface AsrProvider {
    val location: ProviderLocation
    suspend fun open(config: AsrSessionConfig): AsrSession
}

interface TranslationProvider {
    suspend fun translate(text: String, from: Lang, to: Lang): String
}

data class SpeakerIdentity(val clusterKey: String, val displayName: String?, val confidence: Float)

/** Voiceprint / clustering. Remote on G1; local when the device has the compute (decision 6). */
interface SpeakerProvider {
    val location: ProviderLocation
    suspend fun resolve(meetingId: String, channel: Channel, diarizationLabel: String?, startMs: Long, endMs: Long): SpeakerIdentity
}

data class MinutesRequest(
    val record: MeetingRecord,
    val templateId: String,
    val outputLang: Lang,
)

interface MinutesProvider {
    suspend fun generate(request: MinutesRequest, onProgress: (Float) -> Unit): Summary
    suspend fun createShareLink(record: MeetingRecord): ShareLink
}

data class ServerCapabilities(
    val asrLangs: Set<Lang>,
    val translationTargets: Map<Lang, Set<Lang>>,
    val templateIds: Set<String>,
    val speakerRecognition: Boolean,
    val modelVersion: String,
)

sealed interface HealthResult {
    data class Ok(val latencyMs: Long, val capabilities: ServerCapabilities) : HealthResult
    data class Unavailable(val reason: String, val error: ProviderException? = null) : HealthResult
    data object NotConfigured : HealthResult
}

interface ServerGateway {
    suspend fun health(): HealthResult
}

class ProviderBundle(
    val location: ProviderLocation,
    /** Which deployment serves this bundle; shown in the floating window and in error messages. */
    val backend: BackendKind,
    val gateway: ServerGateway,
    val asr: AsrProvider,
    val translation: TranslationProvider,
    val speaker: SpeakerProvider,
    val minutes: MinutesProvider,
)
