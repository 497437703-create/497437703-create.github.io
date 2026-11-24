package com.aihuiji.meeting.core.provider.hunyuan

import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.SourceRef
import com.aihuiji.meeting.core.model.Summary
import com.aihuiji.meeting.core.model.SummaryCard
import com.aihuiji.meeting.core.model.SummaryItem
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.minutes.HeuristicMinutesEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Prompts and response parsing shared by the cloud and SCH-900S Hunyuan deployments. */
object HunyuanPrompts {
    /** Long meetings are trimmed in the middle so the request stays inside the model context. */
    const val MAX_TRANSCRIPT_CHARS = 60_000
    const val MAX_ITEMS_PER_CARD = 6

    private fun zhName(lang: Lang) = when (lang) {
        Lang.ZH -> "中文"
        Lang.EN -> "英语"
        Lang.FR -> "法语"
        Lang.DE -> "德语"
        Lang.JA -> "日语"
    }

    private fun enName(lang: Lang) = when (lang) {
        Lang.ZH -> "Chinese"
        Lang.EN -> "English"
        Lang.FR -> "French"
        Lang.DE -> "German"
        Lang.JA -> "Japanese"
    }

    /** Hunyuan-MT's published prompt format: Chinese instruction when Chinese is involved, English otherwise. */
    fun translation(text: String, from: Lang, to: Lang): List<ChatMessage> {
        val prompt = if (from == Lang.ZH || to == Lang.ZH) {
            "把下面的文本翻译成${zhName(to)}，不要额外解释。\n\n$text"
        } else {
            "Translate the following segment into ${enName(to)}, without additional explanation.\n\n$text"
        }
        return listOf(ChatMessage("user", prompt))
    }

    fun cleanTranslation(output: String): String = output.trim().removeSurrounding("\"").trim()

    private fun cardMeaning(type: CardType) = when (type) {
        CardType.TOPICS -> "讨论的议题"
        CardType.DECISIONS -> "形成的决议"
        CardType.TODOS -> "待办事项（owner 填负责人，due 填截止时间，没有则省略）"
        CardType.RISKS -> "风险"
        CardType.PROGRESS -> "进展"
        CardType.ISSUES -> "问题与阻塞"
        CardType.CANDIDATE_POINTS -> "候选人要点"
        CardType.EVALUATION -> "评估"
        CardType.FOLLOW_UP_QUESTIONS -> "需要追问的问题"
        CardType.CONCLUSION -> "结论"
        CardType.KNOWLEDGE_POINTS -> "知识点"
        CardType.QA -> "问答"
        CardType.REVIEW_ITEMS -> "评审项"
        CardType.VIEWPOINTS -> "主要观点"
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** One numbered line per final segment; the numbers are what the model cites in `refs`. */
    fun transcriptLines(record: MeetingRecord): List<String> = record.finalSegments.mapIndexed { i, seg ->
        val who = record.speaker(seg.speakerId)?.let { it.name ?: HeuristicMinutesEngine.speakerLabel(record.primaryLang, it.ordinal) } ?: "?"
        "[${i + 1}] $who ${clock(seg.startMs)} ${seg.text}"
    }

    fun fitTranscript(lines: List<String>, maxChars: Int = MAX_TRANSCRIPT_CHARS): String {
        val all = lines.joinToString("\n")
        if (all.length <= maxChars) return all
        val head = mutableListOf<String>()
        val tail = ArrayDeque<String>()
        var used = 0
        var i = 0
        var j = lines.lastIndex
        while (i <= j) {
            val fromHead = head.size <= tail.size
            val line = if (fromHead) lines[i] else lines[j]
            if (used + line.length + 1 > maxChars) break
            used += line.length + 1
            if (fromHead) { head += line; i++ } else { tail.addFirst(line); j-- }
        }
        val skipped = lines.size - head.size - tail.size
        return (head + "……（中间省略 $skipped 段）" + tail).joinToString("\n")
    }

    fun minutes(request: MinutesRequest): List<ChatMessage> {
        val record = request.record
        val cards = Templates.byId(request.templateId).cards
        val keys = cards.joinToString("\n") { "- ${it.name}: ${cardMeaning(it)}" }
        val marks = record.marks.mapNotNull { m ->
            val idx = record.finalSegments.indexOfFirst { it.id == m.segmentId }
            if (idx >= 0) "[${idx + 1}] 被标记为 ${m.tag.name}" else null
        }
        val notes = record.notes.map { "速记：${it.text}" }
        val system = """
            你是会议纪要助手。根据带编号的会议转写生成结构化纪要，只输出一个 JSON 对象，不要输出解释或 Markdown。
            格式：{"overview":{"text":"一段总体概述","refs":[编号]},"cards":{"<键>":[{"text":"条目","refs":[编号],"owner":"负责人","due":"截止时间"}]}}
            cards 只能使用以下键，每个键最多 $MAX_ITEMS_PER_CARD 条：
            $keys
            refs 必须是转写里真实出现的编号；没有转写依据的条目 refs 写空数组。
            输出语言：${zhName(request.outputLang)}。
        """.trimIndent()
        val user = buildString {
            append("会议标题：").append(record.title).append('\n')
            append("会议转写：\n").append(fitTranscript(transcriptLines(record)))
            if (marks.isNotEmpty() || notes.isNotEmpty()) {
                append("\n\n与会者标记与速记：\n").append((marks + notes).joinToString("\n"))
            }
        }
        return listOf(ChatMessage("system", system), ChatMessage("user", user))
    }

    /** Strips code fences / chatter around the first JSON object in a model reply. */
    fun extractJson(output: String): JsonObject {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in model output" }
        return HunyuanChatClient.json.parseToJsonElement(output.substring(start, end + 1)).jsonObject
    }

    fun parseMinutes(output: String, request: MinutesRequest, nowMs: Long = System.currentTimeMillis()): Summary {
        val root = extractJson(output)
        val segments = request.record.finalSegments
        val template = Templates.byId(request.templateId)

        fun refs(el: JsonObject): List<SourceRef> = (el["refs"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            .mapNotNull { n -> segments.getOrNull(n - 1) }
            .distinctBy(Segment::id)
            .map { SourceRef(it.id, it.startMs, it.endMs) }

        fun str(el: JsonObject, key: String) = (el[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val overviewObj = root["overview"]
        val overview = when (overviewObj) {
            is JsonObject -> SummaryItem("overview", str(overviewObj, "text").orEmpty(), refs(overviewObj)).let { it.copy(inferred = it.sourceRefs.isEmpty()) }
            is JsonPrimitive -> SummaryItem("overview", overviewObj.content, inferred = true)
            else -> SummaryItem("overview", "", inferred = true)
        }
        val cardsObj = root["cards"] as? JsonObject ?: JsonObject(emptyMap())
        val cards = template.cards.map { type ->
            val items = (cardsObj[type.name] as? JsonArray).orEmpty().take(MAX_ITEMS_PER_CARD).mapIndexedNotNull { i, el ->
                val obj = when (el) {
                    is JsonObject -> el
                    is JsonPrimitive -> JsonObject(mapOf("text" to el))
                    else -> return@mapIndexedNotNull null
                }
                val text = str(obj, "text") ?: return@mapIndexedNotNull null
                val r = refs(obj)
                SummaryItem(
                    id = "${type.name}-$i",
                    text = text,
                    sourceRefs = r,
                    inferred = r.isEmpty(),
                    owner = if (type == CardType.TODOS) str(obj, "owner") else null,
                    due = if (type == CardType.TODOS) str(obj, "due") else null,
                )
            }
            SummaryCard(type, items)
        }
        return Summary(template.id, request.outputLang, overview, cards, nowMs)
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
