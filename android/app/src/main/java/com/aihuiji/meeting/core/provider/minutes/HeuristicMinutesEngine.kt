package com.aihuiji.meeting.core.provider.minutes

import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.ContentTag
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.SourceRef
import com.aihuiji.meeting.core.model.Summary
import com.aihuiji.meeting.core.model.SummaryCard
import com.aihuiji.meeting.core.model.SummaryItem
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.provider.MinutesRequest

/**
 * Offline stand-in for the server minutes model. It never invents content: every item is a
 * transcript segment, so every item carries a real `sourceRefs` anchor for 精准回溯.
 */
object HeuristicMinutesEngine {
    private const val MAX_ITEMS_PER_CARD = 6

    private val keywordRules: List<Pair<ContentTag, Regex>> = listOf(
        ContentTag.DECISION to Regex("决定|定了|就定|通过|确定|維持|決定|decid|go with|agreed|retenons|beschlossen|nehmen wir", RegexOption.IGNORE_CASE),
        ContentTag.TODO to Regex("我来|负责|提交|给出|完成|担当|提出|I'll|I will|by (friday|monday)|je m'en charge|übernehme", RegexOption.IGNORE_CASE),
        ContentTag.RISK to Regex("风险|延期|紧张|不稳定|リスク|遅れ|risk|delay|slip|unstable|risque|Risiko", RegexOption.IGNORE_CASE),
        ContentTag.QUESTION to Regex("[?？]"),
        ContentTag.PROGRESS to Regex("已经完成|完成了|完了|finished|completed|terminé|abgeschlossen"),
        ContentTag.TOPIC to Regex("讨论|议题|规划|話し合|discuss|agenda|plan", RegexOption.IGNORE_CASE),
    )

    fun keywordTags(text: String): Set<ContentTag> = keywordRules.filter { it.second.containsMatchIn(text) }.map { it.first }.toSet()

    fun summarize(request: MinutesRequest, extraTagger: (String) -> Set<ContentTag> = { emptySet() }): Summary {
        val record = request.record
        val out = request.outputLang
        val template = Templates.byId(request.templateId)
        val segments = record.finalSegments

        val markTags = buildMap<String, MutableSet<ContentTag>> {
            fun add(segmentId: String?, tag: MarkTag?) {
                if (segmentId == null || tag == null) return
                getOrPut(segmentId) { mutableSetOf() } += tag.toContentTag()
            }
            record.marks.forEach { add(it.segmentId, it.tag) }
            record.notes.forEach { add(it.segmentId, it.tag) }
        }

        val tagged = segments.map { seg ->
            val tags = extraTagger(seg.text).ifEmpty { keywordTags(seg.text) } + markTags[seg.id].orEmpty()
            seg to tags
        }

        fun textOf(seg: Segment) = if (seg.lang == out) seg.text else seg.translations[out] ?: seg.text
        fun ref(seg: Segment) = SourceRef(seg.id, seg.startMs, seg.endMs)
        fun ownerOf(seg: Segment): String? = record.speaker(seg.speakerId)?.let { sp -> sp.name ?: speakerLabel(out, sp.ordinal) }

        val cards = template.cards.map { type ->
            val items = tagged.filter { (_, tags) -> tags.any { it in type.sources } }
                .take(MAX_ITEMS_PER_CARD)
                .map { (seg, _) ->
                    SummaryItem(
                        id = "${type.name}-${seg.id}",
                        text = textOf(seg),
                        sourceRefs = listOf(ref(seg)),
                        owner = if (type == CardType.TODOS) ownerOf(seg) else null,
                    )
                }
            SummaryCard(type, items)
        }

        val topics = tagged.filter { ContentTag.TOPIC in it.second }.map { it.first }
        val decisions = tagged.count { ContentTag.DECISION in it.second }
        val todos = tagged.count { ContentTag.TODO in it.second }
        val speakers = segments.map { it.speakerId }.distinct().size
        val overviewSource = (topics.take(2).ifEmpty { segments.take(1) })
        val overview = SummaryItem(
            id = "overview",
            text = overviewSentence(out, overviewSource.firstOrNull()?.let(::textOf).orEmpty(), speakers, decisions, todos),
            sourceRefs = overviewSource.map(::ref),
            inferred = overviewSource.isEmpty(),
        )

        return Summary(
            templateId = template.id,
            lang = out,
            overview = overview,
            cards = cards,
            generatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun MarkTag.toContentTag() = when (this) {
        MarkTag.KEY -> ContentTag.TOPIC
        MarkTag.TODO -> ContentTag.TODO
        MarkTag.DECISION -> ContentTag.DECISION
        MarkTag.QUESTION -> ContentTag.QUESTION
    }

    fun speakerLabel(lang: Lang, ordinal: Int): String = when (lang) {
        Lang.ZH -> "发言人 $ordinal"
        Lang.EN -> "Speaker $ordinal"
        Lang.FR -> "Intervenant $ordinal"
        Lang.DE -> "Sprecher $ordinal"
        Lang.JA -> "話者 $ordinal"
    }

    private fun overviewSentence(lang: Lang, lead: String, speakers: Int, decisions: Int, todos: Int): String {
        val trimmed = lead.trimEnd('。', '.', '！', '!', ' ')
        return when (lang) {
            Lang.ZH -> "本次会议共 $speakers 位发言人，主要内容：$trimmed。形成 $decisions 项决议、$todos 项待办。"
            Lang.EN -> "$speakers speakers. Main thread: $trimmed. Outcome: $decisions decisions and $todos action items."
            Lang.FR -> "$speakers intervenants. Sujet principal : $trimmed. Résultat : $decisions décisions et $todos actions."
            Lang.DE -> "$speakers Sprecher. Hauptthema: $trimmed. Ergebnis: $decisions Beschlüsse und $todos Aufgaben."
            Lang.JA -> "発言者 $speakers 名。主な内容：$trimmed。決定事項 $decisions 件、ToDo $todos 件。"
        }
    }
}
