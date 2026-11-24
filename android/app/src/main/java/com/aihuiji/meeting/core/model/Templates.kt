package com.aihuiji.meeting.core.model

import kotlinx.serialization.Serializable

/**
 * Normalised topic tags a minutes engine assigns to transcript segments. Templates map these to
 * their own card types, so one tagging pass can feed every template (F-24 template switching).
 */
@Serializable
enum class ContentTag { TOPIC, DECISION, TODO, RISK, PROGRESS, ISSUE, QUESTION, VIEWPOINT }

@Serializable
enum class CardType(val sources: Set<ContentTag>) {
    TOPICS(setOf(ContentTag.TOPIC)),
    DECISIONS(setOf(ContentTag.DECISION)),
    TODOS(setOf(ContentTag.TODO)),
    RISKS(setOf(ContentTag.RISK)),
    PROGRESS(setOf(ContentTag.PROGRESS)),
    ISSUES(setOf(ContentTag.ISSUE, ContentTag.RISK)),
    CANDIDATE_POINTS(setOf(ContentTag.VIEWPOINT, ContentTag.PROGRESS)),
    EVALUATION(setOf(ContentTag.DECISION, ContentTag.RISK)),
    FOLLOW_UP_QUESTIONS(setOf(ContentTag.QUESTION)),
    CONCLUSION(setOf(ContentTag.DECISION)),
    KNOWLEDGE_POINTS(setOf(ContentTag.TOPIC, ContentTag.VIEWPOINT)),
    QA(setOf(ContentTag.QUESTION)),
    REVIEW_ITEMS(setOf(ContentTag.TOPIC)),
    VIEWPOINTS(setOf(ContentTag.VIEWPOINT)),
}

data class SummaryTemplate(
    val id: String,
    val cards: List<CardType>,
)

/** First batch per framework §9.4 / F-24. M1 offers manual selection of all of them. */
object Templates {
    val GENERAL = SummaryTemplate("general", listOf(CardType.TOPICS, CardType.DECISIONS, CardType.TODOS))
    val PROJECT = SummaryTemplate("project", listOf(CardType.TOPICS, CardType.DECISIONS, CardType.TODOS, CardType.RISKS))
    val STANDUP = SummaryTemplate("standup", listOf(CardType.PROGRESS, CardType.ISSUES, CardType.TODOS))
    val REVIEW = SummaryTemplate("review", listOf(CardType.REVIEW_ITEMS, CardType.ISSUES, CardType.CONCLUSION, CardType.TODOS))
    val INTERVIEW_ACCESS = SummaryTemplate("interview", listOf(CardType.TOPICS, CardType.VIEWPOINTS, CardType.FOLLOW_UP_QUESTIONS))
    val HIRING = SummaryTemplate("hiring", listOf(CardType.CANDIDATE_POINTS, CardType.EVALUATION, CardType.FOLLOW_UP_QUESTIONS, CardType.CONCLUSION))
    val TRAINING = SummaryTemplate("training", listOf(CardType.KNOWLEDGE_POINTS, CardType.QA, CardType.TODOS))

    val all = listOf(GENERAL, PROJECT, STANDUP, REVIEW, INTERVIEW_ACCESS, HIRING, TRAINING)

    fun byId(id: String?): SummaryTemplate = all.firstOrNull { it.id == id } ?: GENERAL
}
