package com.aihuiji.meeting.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.audio.AudioDevices
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.core.provider.HealthResult
import com.aihuiji.meeting.meeting.LiveMeeting
import com.aihuiji.meeting.service.CaptionBarService
import com.aihuiji.meeting.ui.meeting.EndConfirmDialog
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.ui.components.BrandWordmark
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.ToolAction
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data class Ok(val demo: Boolean) : ServerStatus
    data object Down : ServerStatus
}

@Composable
fun MeetingCard(record: MeetingRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.height(200.dp).glass(24.dp).pressable(onClick = onClick).padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (record.mode == MeetingMode.VIDEO) Icons.Outlined.VideoCall else Icons.Outlined.Groups, null, tint = Palette.Brand400, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(record.title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.startedAtEpochMs))} · ${formatClock(record.durationMs)}",
            style = Type.Caption.copy(fontFeatureSettings = "tnum"),
        )
        Spacer(Modifier.height(8.dp))
        val (label, color) = when (record.state) {
            MeetingState.DONE -> stringResource(R.string.state_done, stringResource(Labels.template(record.templateId))) to Palette.Success
            MeetingState.GENERATING -> stringResource(R.string.state_generating) to Palette.Brand400
            MeetingState.FAILED -> stringResource(R.string.state_failed) to Palette.Danger
            else -> stringResource(R.string.state_unfinished) to Palette.Warning
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
            Spacer(Modifier.width(10.dp))
            Text(label, style = Type.Caption, color = Palette.Text1)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Palette.Text2)
        }
    }
}

@Composable
fun RecoveryBanner(record: MeetingRecord, onRecover: (MeetingRecord) -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(24.dp, color = Palette.Bg2).padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Restore, null, tint = Palette.Warning, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(20.dp))
        Text(stringResource(R.string.recovery_banner, record.title), style = Type.Label, modifier = Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.action_ignore), onDismiss, height = 72.dp)
        Spacer(Modifier.width(16.dp))
        PrimaryButton(stringResource(R.string.action_recover), { onRecover(record) }, height = 72.dp)
    }
}
