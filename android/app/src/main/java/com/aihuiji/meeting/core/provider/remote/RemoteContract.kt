package com.aihuiji.meeting.core.provider.remote

import com.aihuiji.meeting.core.model.Summary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * PROVISIONAL client contract for both server profiles (enterprise SCH-900S and Lenovo cloud).
 * The real interface documents are not available yet; these shapes are what the client sends and
 * expects so the server side can either implement them or a thin adapter can map onto them.
 * Everything server-specific is isolated in this package; the UI only sees the provider interfaces.
 *
 * Auth: `Authorization: Bearer <token>` on every call.
 *
 * - `GET  /v1/health`                         → [HealthDto]
 * - `WSS  /v1/asr/stream?meeting=&lang=&auto=&channels=local,remote`
 *     client → server binary: `[u8 channel(0=local,1=remote)][i64 BE offsetMs][PCM16 LE 16 kHz mono]`
 *     client → server text:   [ControlDto] (`pause`, `resume`, `end`)
 *     server → client text:   [AsrEventDto] (`partial`, `final`, `closed`)
 * - `POST /v1/translate`                      [TranslateRequestDto] → [TranslateResponseDto]
 * - `POST /v1/speakers/resolve`               [SpeakerResolveRequestDto] → [SpeakerResolveResponseDto]
 * - `POST /v1/minutes/jobs`                   [MinutesJobRequestDto] → [MinutesJobDto]
 * - `GET  /v1/minutes/jobs/{jobId}`           → [MinutesJobDto] (poll until `done` / `failed`)
 * - `POST /v1/meetings/{meetingId}/share`     [ShareRequestDto] → [ShareResponseDto]
 */
object RemoteContract {
    const val CHANNEL_LOCAL: Byte = 0
    const val CHANNEL_REMOTE: Byte = 1

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

@Serializable
data class HealthDto(
    val status: String,
    val modelVersion: String = "",
    val asrLangs: List<String> = emptyList(),
    val translationPairs: Map<String, List<String>> = emptyMap(),
    val templates: List<String> = emptyList(),
    val speakerRecognition: Boolean = false,
)

@Serializable
data class ControlDto(val type: String)

@Serializable
data class AsrEventDto(
    val type: String,
    val seg: String = "",
    val ch: String = "local",
    val text: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val lang: String = "",
    val spk: String? = null,
)

@Serializable
data class TranslateRequestDto(val text: String, val from: String, val to: String)

@Serializable
data class TranslateResponseDto(val text: String)

@Serializable
data class SpeakerResolveRequestDto(
    val meetingId: String,
    val channel: String,
    val diarizationLabel: String?,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class SpeakerResolveResponseDto(val clusterKey: String, val name: String? = null, val confidence: Float = 0f)

@Serializable
data class MinutesJobRequestDto(
    val meetingId: String,
    val templateId: String,
    val outputLang: String,
    /** The full [com.aihuiji.meeting.core.model.MeetingRecord] JSON (segments, speakers, marks, notes). */
    val record: kotlinx.serialization.json.JsonElement,
)

@Serializable
data class MinutesJobDto(
    val jobId: String,
    val status: String,
    val progress: Float = 0f,
    val summary: Summary? = null,
    val error: String? = null,
)

@Serializable
data class ShareRequestDto(val code: String, val ttlHours: Int = 168)

@Serializable
data class ShareResponseDto(val url: String, val expiresAtEpochMs: Long)
