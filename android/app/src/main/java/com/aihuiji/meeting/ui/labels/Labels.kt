package com.aihuiji.meeting.ui.labels

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.ProfileKind
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.core.model.TranslationDisplay
import com.aihuiji.meeting.ui.theme.Palette

object Labels {
    @StringRes
    fun tag(tag: MarkTag): Int = when (tag) {
        MarkTag.KEY -> R.string.tag_key
        MarkTag.TODO -> R.string.tag_todo
        MarkTag.DECISION -> R.string.tag_decision
        MarkTag.QUESTION -> R.string.tag_question
    }

    fun tagIcon(tag: MarkTag): ImageVector = when (tag) {
        MarkTag.KEY -> Icons.Outlined.Star
        MarkTag.TODO -> Icons.Outlined.TaskAlt
        MarkTag.DECISION -> Icons.Outlined.Gavel
        MarkTag.QUESTION -> Icons.Outlined.HelpOutline
    }

    /** Tag colours derive from the brand-compatible speaker palette. */
    fun tagColor(tag: MarkTag): Color = when (tag) {
        MarkTag.KEY -> Palette.Brand400
        MarkTag.TODO -> Color(0xFF3FB6A8)
        MarkTag.DECISION -> Color(0xFFE8A33D)
        MarkTag.QUESTION -> Color(0xFF5B8DEF)
    }

    @StringRes
    fun card(type: CardType): Int = when (type) {
        CardType.TOPICS -> R.string.card_topics
        CardType.DECISIONS -> R.string.card_decisions
        CardType.TODOS -> R.string.card_todos
        CardType.RISKS -> R.string.card_risks
        CardType.PROGRESS -> R.string.card_progress
        CardType.ISSUES -> R.string.card_issues
        CardType.CANDIDATE_POINTS -> R.string.card_candidate_points
        CardType.EVALUATION -> R.string.card_evaluation
        CardType.FOLLOW_UP_QUESTIONS -> R.string.card_follow_up
        CardType.CONCLUSION -> R.string.card_conclusion
        CardType.KNOWLEDGE_POINTS -> R.string.card_knowledge
        CardType.QA -> R.string.card_qa
        CardType.REVIEW_ITEMS -> R.string.card_review_items
        CardType.VIEWPOINTS -> R.string.card_viewpoints
    }

    fun cardIcon(type: CardType): ImageVector = when (type) {
        CardType.TOPICS, CardType.REVIEW_ITEMS -> Icons.Outlined.ListAlt
        CardType.DECISIONS, CardType.CONCLUSION -> Icons.Outlined.Gavel
        CardType.TODOS -> Icons.Outlined.TaskAlt
        CardType.RISKS, CardType.ISSUES -> Icons.Outlined.WarningAmber
        CardType.PROGRESS -> Icons.Outlined.TrendingUp
        CardType.CANDIDATE_POINTS, CardType.EVALUATION -> Icons.Outlined.CheckCircle
        CardType.FOLLOW_UP_QUESTIONS, CardType.QA -> Icons.Outlined.QuestionAnswer
        CardType.KNOWLEDGE_POINTS -> Icons.Outlined.School
        CardType.VIEWPOINTS -> Icons.Outlined.RecordVoiceOver
    }

    fun cardAccent(type: CardType): Color = when (type) {
        CardType.DECISIONS, CardType.CONCLUSION, CardType.EVALUATION -> Color(0xFFE8A33D)
        CardType.TODOS -> Color(0xFF3FB6A8)
        CardType.RISKS, CardType.ISSUES -> Color(0xFFE2618F)
        CardType.FOLLOW_UP_QUESTIONS, CardType.QA -> Color(0xFF5B8DEF)
        else -> Palette.Brand400
    }

    @StringRes
    fun template(id: String): Int = when (id) {
        "project" -> R.string.template_project
        "standup" -> R.string.template_standup
        "review" -> R.string.template_review
        "interview" -> R.string.template_interview
        "hiring" -> R.string.template_hiring
        "training" -> R.string.template_training
        else -> R.string.template_general
    }

    fun templateIcon(id: String): ImageVector = when (id) {
        "project" -> Icons.Outlined.Flag
        "standup" -> Icons.Outlined.TrendingUp
        "review" -> Icons.Outlined.CheckCircle
        "interview" -> Icons.Outlined.RecordVoiceOver
        "hiring" -> Icons.Outlined.QuestionAnswer
        "training" -> Icons.Outlined.School
        else -> Icons.Outlined.Lightbulb
    }

    @StringRes
    fun profile(kind: ProfileKind): Int = when (kind) {
        ProfileKind.ENTERPRISE -> R.string.profile_enterprise
        ProfileKind.LENOVO_CLOUD -> R.string.profile_lenovo
    }

    @StringRes
    fun display(d: TranslationDisplay): Int = when (d) {
        TranslationDisplay.INTERLEAVED -> R.string.display_interleaved
        TranslationDisplay.BLOCK -> R.string.display_block
        TranslationDisplay.TRANSLATION_ONLY -> R.string.display_translation_only
    }

    @StringRes
    fun size(s: CaptionSize): Int = when (s) {
        CaptionSize.S -> R.string.size_s
        CaptionSize.M -> R.string.size_m
        CaptionSize.L -> R.string.size_l
        CaptionSize.XL -> R.string.size_xl
    }

    fun speakerName(context: Context, speaker: Speaker?): String =
        speaker?.name ?: context.getString(R.string.speaker_n, speaker?.ordinal ?: 0)

    @Composable
    fun speakerName(speaker: Speaker?): String = speaker?.name ?: stringResource(R.string.speaker_n, speaker?.ordinal ?: 0)
}
