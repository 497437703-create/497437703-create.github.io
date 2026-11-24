package com.aihuiji.meeting.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.meeting.MeetingController
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

/** S09: reset steps + minutes progress. Users may leave; the summary opens when ready. */
@Composable
fun GeneratingScreen(meetingId: String, onDone: () -> Unit, onHome: () -> Unit) {
    val container = LocalContext.current.container
    val generation by container.meetingController.generation.collectAsStateWithLifecycle()
    val meetings by container.repository.meetings.collectAsStateWithLifecycle()
    val record = meetings.firstOrNull { it.id == meetingId }
    val gen = generation?.takeIf { it.meetingId == meetingId }

    LaunchedEffect(record?.state, gen?.done) {
        if (record?.state == MeetingState.DONE) onDone()
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg0), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(1100.dp)) {
            val error = gen?.error ?: record?.failureReason.takeIf { record?.state == MeetingState.FAILED }
            if (error == null) {
                Waveform(level = 0.9f, maxHeight = 160.dp, barWidth = 24.dp, gap = 18.dp)
                Spacer(Modifier.height(40.dp))
                Text(stringResource(R.string.generating_title), style = Type.Display)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.generating_template, stringResource(Labels.template(record?.templateId ?: "general"))),
                    style = Type.Body,
                    color = Palette.Text2,
                )
                Spacer(Modifier.height(40.dp))
                val p = gen?.progress ?: 0f
                Box(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape).background(Palette.Bg2)) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(Palette.BrandGradient))
                }
                Spacer(Modifier.height(12.dp))
                Text("${(p * 100).toInt()}%", style = Type.Label, color = Palette.Text2)
            } else {
                Icon(Icons.Outlined.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(120.dp))
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (error == MeetingController.RECORD_ONLY) R.string.generating_record_only else R.string.generating_failed),
                    style = Type.TitleL,
                    textAlign = TextAlign.Center,
                )
                if (error != MeetingController.RECORD_ONLY) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = Type.Caption, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(48.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ResetStep(stringResource(R.string.reset_mic_off))
                ResetStep(stringResource(R.string.reset_cache_cleared))
                ResetStep(stringResource(R.string.reset_audio_saved))
            }
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SecondaryButton(stringResource(R.string.action_back_home), onHome)
                if (error != null && record != null) {
                    PrimaryButton(stringResource(R.string.action_retry_generate), { container.meetingController.regenerate(record.id, record.templateId) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.generating_leave_hint), style = Type.Caption)
        }
    }
}

@Composable
private fun ResetStep(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 160.dp)) {
        Icon(Icons.Outlined.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = Type.Body)
    }
}
