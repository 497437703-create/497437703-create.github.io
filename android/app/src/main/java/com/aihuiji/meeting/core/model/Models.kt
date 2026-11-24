package com.aihuiji.meeting.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Lang(val code: String, val nativeName: String, val tag: String) {
    ZH("zh", "中文", "ZH"),
    EN("en", "English", "EN"),
    FR("fr", "Français", "FR"),
    DE("de", "Deutsch", "DE"),
    JA("ja", "日本語", "JA");

    /** Chinese and Japanese are segmented per character for streaming partials and line breaking. */
    val isCjk: Boolean get() = this == ZH || this == JA

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

@Serializable
enum class MeetingMode { IN_ROOM, VIDEO }

@Serializable
enum class Channel { LOCAL, REMOTE }

/** F-01 lifecycle. M1 drives IDLE → PREPARING → LIVE ⇄ PAUSED → ENDING → GENERATING → DONE/FAILED. */
@Serializable
enum class MeetingState { IDLE, PREPARING, LIVE, PAUSED, ENDING, GENERATING, DONE, FAILED }

@Serializable
enum class TranslationDisplay { INTERLEAVED, BLOCK, TRANSLATION_ONLY }

@Serializable
enum class CaptionSize { S, M, L, XL }

@Serializable
enum class MarkTag { KEY, TODO, DECISION, QUESTION }

@Serializable
enum class LinkState { CONNECTING, CONNECTED, RECONNECTING, BACKFILLED, OFFLINE }

@Serializable
data class Speaker(
    val id: String,
    val ordinal: Int,
    val channel: Channel,
    val colorIndex: Int,
    val name: String? = null,
)

@Serializable
data class Segment(
    val id: String,
    val speakerId: String,
    val channel: Channel,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val lang: Lang,
    val isFinal: Boolean,
    val translations: Map<Lang, String> = emptyMap(),
)

@Serializable
data class Mark(
    val id: String,
    val atMs: Long,
    val tag: MarkTag,
    val segmentId: String? = null,
)

/** A speed note: either an excerpt of a caption segment or free text, optionally tagged. */
@Serializable
data class Note(
    val id: String,
    val atMs: Long,
    val text: String,
    val segmentId: String? = null,
    val speakerId: String? = null,
    val tag: MarkTag? = null,
)

@Serializable
data class TimeRange(val startMs: Long, val endMs: Long)

@Serializable
data class SourceRef(val segmentId: String, val startMs: Long, val endMs: Long)

@Serializable
data class SummaryItem(
    val id: String,
    val text: String,
    val sourceRefs: List<SourceRef> = emptyList(),
    /** True when the model produced the item without a transcript anchor (F-25: shown as 推断). */
    val inferred: Boolean = false,
    val owner: String? = null,
    val due: String? = null,
)

@Serializable
data class SummaryCard(
    val type: CardType,
    val items: List<SummaryItem>,
)

@Serializable
data class Summary(
    val templateId: String,
    val lang: Lang,
    val overview: SummaryItem,
    val cards: List<SummaryCard>,
    val generatedAtEpochMs: Long,
)

@Serializable
data class ShareLink(val url: String, val code: String, val expiresAtEpochMs: Long)

@Serializable
data class MeetingRecord(
    val id: String,
    val code: String,
    val title: String,
    val mode: MeetingMode,
    val startedAtEpochMs: Long,
    val durationMs: Long = 0,
    val state: MeetingState,
    val primaryLang: Lang,
    val autoDetect: Boolean = false,
    val targetLangs: List<Lang> = emptyList(),
    val dualChannel: Boolean = false,
    val profileId: String,
    val templateId: String,
    val speakers: List<Speaker> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val notes: List<Note> = emptyList(),
    val pausedRanges: List<TimeRange> = emptyList(),
    val summary: Summary? = null,
    /** Earlier summaries kept when the template is switched (F-24: 保留原版本). */
    val previousSummaries: List<Summary> = emptyList(),
    val share: ShareLink? = null,
    val audioFiles: Map<Channel, String> = emptyMap(),
    val failureReason: String? = null,
) {
    val finalSegments: List<Segment> get() = segments.filter { it.isFinal }
    fun speaker(id: String): Speaker? = speakers.firstOrNull { it.id == id }
}
