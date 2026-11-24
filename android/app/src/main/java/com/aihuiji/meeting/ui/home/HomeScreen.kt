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

/** S02 home: two big start cards, status, recent meetings. Starting needs ≤ 2 touches. */
@Composable
fun HomeScreen(
    onOpenMeeting: (String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onOpenLive: () -> Unit,
) {
    val ctx = LocalContext.current
    val container = ctx.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val meetings by container.repository.meetings.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf<ServerStatus>(ServerStatus.Checking) }
    var showStart by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val live by container.meetingController.live.collectAsStateWithLifecycle()
    var recordOnlyHint by remember { mutableStateOf(false) }
    var selfCheckOnly by remember { mutableStateOf(false) }
    var dismissedRecovery by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(settings.activeProfileId, settings.activeProfile) {
        while (true) {
            val bundle = container.providers.create(settings.activeProfile)
            server = when (bundle.gateway.health()) {
                is HealthResult.Ok -> ServerStatus.Ok(bundle.location == ProviderLocation.DEMO)
                else -> ServerStatus.Down
            }
            delay(30_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val mic = remember(now) { AudioDevices.bestMic(ctx) }

    Box(Modifier.fillMaxSize().background(Palette.Bg0)) {
        HomeBackdrop()
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandWordmark(size = 72.dp)
                Spacer(Modifier.weight(1f))
                val profileName = stringResource(Labels.profile(settings.activeProfile.kind))
                when (val s = server) {
                    ServerStatus.Checking -> StatusPill(stringResource(R.string.server_checking, profileName), Palette.Text2, icon = Icons.Outlined.Cloud)
                    is ServerStatus.Ok -> StatusPill(
                        stringResource(if (s.demo) R.string.server_connected_demo else R.string.server_connected, profileName),
                        Palette.Success, icon = Icons.Outlined.Cloud, onClick = onSettings,
                    )
                    ServerStatus.Down -> StatusPill(stringResource(R.string.server_down, profileName), Palette.Danger, icon = Icons.Outlined.CloudOff, onClick = onSettings)
                }
                Spacer(Modifier.width(16.dp))
                when {
                    !micGranted -> StatusPill(stringResource(R.string.mic_status_no_permission), Palette.Danger, icon = Icons.Outlined.MicOff, onClick = { selfCheckOnly = true })
                    mic == null -> StatusPill(stringResource(R.string.mic_status_missing), Palette.Danger, icon = Icons.Outlined.MicOff, onClick = { selfCheckOnly = true })
                    !mic.external -> StatusPill(stringResource(R.string.mic_status_builtin), Palette.Warning, icon = Icons.Outlined.Mic, onClick = { selfCheckOnly = true })
                    else -> StatusPill(stringResource(R.string.mic_status_ok, mic.name), Palette.Success, icon = Icons.Outlined.Mic)
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(now)), style = Type.TitleM.copy(fontFeatureSettings = "tnum"))
                    Text(SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date(now)), style = Type.Caption)
                }
            }

            val unfinished = remember(meetings) { container.repository.unfinished(container.meetingController.live.value?.record?.id) }
            if (unfinished.isNotEmpty() && !dismissedRecovery) {
                Spacer(Modifier.height(24.dp))
                RecoveryBanner(unfinished.first(), onRecover = {
                    container.meetingController.recover(it.id)
                    onOpenMeeting(it.id)
                }, onDismiss = { dismissedRecovery = true })
            }

            Spacer(Modifier.weight(0.6f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val running = live
                if (running == null) {
                    StartHero(
                        title = stringResource(R.string.start_meeting),
                        subtitle = stringResource(R.string.start_meeting_sub),
                        onClick = { showStart = true; recordOnlyHint = server == ServerStatus.Down },
                    )
                } else {
                    val overlayOk = remember(now, running) { android.provider.Settings.canDrawOverlays(ctx) }
                    RunningCard(
                        live = running,
                        overlayOk = overlayOk,
                        onShowBar = {
                            if (overlayOk) {
                                container.meetingController.setBarMinimized(false)
                                CaptionBarService.show(ctx)
                                (ctx as? android.app.Activity)?.moveTaskToBack(true)
                            } else onOpenLive()
                        },
                        onEnd = { confirmEnd = true },
                    )
                }
            }
            if (server == ServerStatus.Down) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.server_down_hint),
                    style = Type.Caption,
                    color = Palette.Warning,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.weight(0.6f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_meetings), style = Type.TitleM)
                Spacer(Modifier.weight(1f))
                ToolAction(Icons.Outlined.FactCheck, stringResource(R.string.action_self_check), { selfCheckOnly = true })
                Spacer(Modifier.width(16.dp))
                ToolAction(Icons.Outlined.History, stringResource(R.string.action_all_meetings), onHistory)
                Spacer(Modifier.width(16.dp))
                ToolAction(Icons.Outlined.Settings, stringResource(R.string.action_settings), onSettings)
            }
            Spacer(Modifier.height(20.dp))
            val recent = meetings.take(4)
            if (recent.isEmpty()) {
                Row(Modifier.height(180.dp).fillMaxWidth().glass(24.dp).padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Waveform(level = 0.3f, maxHeight = 56.dp, animate = false)
                    Spacer(Modifier.width(24.dp))
                    Text(stringResource(R.string.recent_empty), style = Type.Body, color = Palette.Text2)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    recent.forEach { MeetingCard(it, Modifier.weight(1f)) { onOpenMeeting(it.id) } }
                    repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (showStart && live == null) {
            StartFlow(serverDown = recordOnlyHint, onCancel = { showStart = false })
        }
        if (confirmEnd && live != null) {
            EndConfirmDialog(onContinue = { confirmEnd = false }, onEnd = { confirmEnd = false; container.meetingController.end() })
        }
        if (selfCheckOnly) {
            SelfCheckOverlay(mode = MeetingMode.VIDEO, standalone = true, onProceed = { selfCheckOnly = false }, onCancel = { selfCheckOnly = false })
        }
    }
}

@Composable
private fun HomeBackdrop() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(Palette.Brand700.copy(alpha = 0.22f), Color.Transparent),
                radius = 1800f,
                center = androidx.compose.ui.geometry.Offset(2600f, -200f),
            ),
        ),
    )
}

private val HeroSize = androidx.compose.ui.unit.DpSize(1240.dp, 380.dp)

@Composable
private fun HeroChip(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.Label, color = Color.White)
    }
}

/** One start button for room and video meetings alike; the video/remote-audio choice lives in the pre-meeting sheet. */
@Composable
private fun StartHero(title: String, subtitle: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(HeroSize)
            .clip(RoundedCornerShape(44.dp))
            .background(Brush.linearGradient(listOf(Palette.Brand400, Palette.Brand500, Palette.Brand700)))
            .background(Palette.GlassTopHighlight)
            .pressable(onClick = onClick)
            .padding(56.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeroChip(Icons.Outlined.Groups, stringResource(R.string.start_meeting_chip_room))
                HeroChip(Icons.Outlined.VideoCall, stringResource(R.string.start_meeting_chip_video))
            }
            Spacer(Modifier.height(36.dp))
            Text(title, style = Type.Display.copy(fontSize = 76.sp, lineHeight = 88.sp), color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(subtitle, style = Type.Body, color = Color.White.copy(alpha = 0.88f))
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            Waveform(level = 0.65f, color = Color.White.copy(alpha = 0.7f), maxHeight = 120.dp, barWidth = 14.dp, gap = 10.dp)
            Spacer(Modifier.width(40.dp))
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(75.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Mic, null, tint = Color.White, modifier = Modifier.size(80.dp))
            }
        }
    }
}

@Composable
private fun RunningCard(live: LiveMeeting, overlayOk: Boolean, onShowBar: () -> Unit, onEnd: () -> Unit) {
    val paused = live.isPaused
    Row(
        Modifier.size(HeroSize).glass(44.dp, color = Palette.Bg1).padding(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (paused) Palette.Warning else Palette.Danger))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.running_title), style = Type.TitleM, color = Palette.Text2)
            }
            Spacer(Modifier.height(16.dp))
            Text(formatClock(live.elapsedMs), style = Type.Display.copy(fontSize = 96.sp, lineHeight = 108.sp, fontFeatureSettings = "tnum"))
            Spacer(Modifier.height(8.dp))
            Text(live.record.title, style = Type.Body, color = Palette.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.End) {
            PrimaryButton(stringResource(if (overlayOk) R.string.running_show_bar else R.string.running_open_live), onShowBar, height = 104.dp)
            SecondaryButton(stringResource(R.string.running_end), onEnd, height = 88.dp)
        }
    }
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
private fun RecoveryBanner(record: MeetingRecord, onRecover: (MeetingRecord) -> Unit, onDismiss: () -> Unit) {
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
