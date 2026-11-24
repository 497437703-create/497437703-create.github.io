package com.aihuiji.meeting

import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.Mark
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.provider.MinutesRequest
import com.aihuiji.meeting.core.provider.minutes.HeuristicMinutesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesEngineTest {
    private fun seg(id: String, speaker: String, start: Long, text: String) =
        Segment(id, speaker, Channel.LOCAL, start, start + 4_000, text, Lang.ZH, isFinal = true)

    private val record = MeetingRecord(
        id = "m1",
        code = "ABCD23",
        title = "项目周会",
        mode = MeetingMode.IN_ROOM,
        startedAtEpochMs = 0,
        durationMs = 60_000,
        state = MeetingState.GENERATING,
        primaryLang = Lang.ZH,
        profileId = "enterprise",
        templateId = "project",
        speakers = listOf(Speaker("S1", 1, Channel.LOCAL, 0), Speaker("S2", 2, Channel.LOCAL, 1, name = "王磊")),
        segments = listOf(
            seg("a", "S1", 0, "今天讨论一下第三季度的发布规划。"),
            seg("b", "S2", 5_000, "我们决定把发布日期定在十月二十号。"),
            seg("c", "S2", 10_000, "测试报告我来负责，周五前提交。"),
            seg("d", "S1", 15_000, "供应链有延期的风险。"),
            seg("e", "S1", 20_000, "这句话没有任何关键词"),
        ),
        marks = listOf(Mark("k1", 20_000, MarkTag.DECISION, segmentId = "e")),
    )

    @Test
    fun everyItemTracesBackToTranscript() {
        val summary = HeuristicMinutesEngine.summarize(MinutesRequest(record, "project", Lang.ZH))
        assertEquals(Templates.PROJECT.cards, summary.cards.map { it.type })
        val ids = record.segments.map { it.id }.toSet()
        summary.cards.flatMap { it.items }.forEach { item ->
            assertTrue(item.text, item.sourceRefs.isNotEmpty())
            item.sourceRefs.forEach { ref ->
                assertTrue(ref.segmentId in ids)
                val s = record.segments.first { it.id == ref.segmentId }
                assertEquals(s.startMs, ref.startMs)
            }
        }
    }

    @Test
    fun keywordsAndMarksFillTemplateCards() {
        val summary = HeuristicMinutesEngine.summarize(MinutesRequest(record, "project", Lang.ZH))
        fun card(t: CardType) = summary.cards.first { it.type == t }.items
        assertTrue(card(CardType.DECISIONS).any { it.sourceRefs.first().segmentId == "b" })
        // A manual 决策 mark promotes an otherwise keyword-free segment.
        assertTrue(card(CardType.DECISIONS).any { it.sourceRefs.first().segmentId == "e" })
        val todo = card(CardType.TODOS).first { it.sourceRefs.first().segmentId == "c" }
        assertEquals("王磊", todo.owner)
        assertTrue(card(CardType.RISKS).any { it.sourceRefs.first().segmentId == "d" })
    }

    @Test
    fun switchingTemplateReusesTheSameTags() {
        Templates.all.forEach { t ->
            val summary = HeuristicMinutesEngine.summarize(MinutesRequest(record, t.id, Lang.ZH))
            assertEquals(t.id, summary.templateId)
            assertEquals(t.cards, summary.cards.map { it.type })
        }
    }
}
