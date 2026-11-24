package com.aihuiji.meeting.ui.meeting

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.AppSettings
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.LinkState
import com.aihuiji.meeting.core.model.Mark
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.ScreenSizeClass
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.meeting.FarStatus
import com.aihuiji.meeting.meeting.LiveMeeting
import com.aihuiji.meeting.meeting.MeetingController
import com.aihuiji.meeting.meeting.MicStatus
import com.aihuiji.meeting.ui.components.BrandLogo
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.DangerButton
import com.aihuiji.meeting.ui.components.EdgeGuard
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.SpeakerAvatar
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.ToolAction
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import com.aihuiji.meeting.vc.VcLauncher
import kotlinx.coroutines.delay

sealed interface LiveOverlay {
    data object More : LiveOverlay
    data object Share : LiveOverlay
    data object End : LiveOverlay
    data class SegmentMenu(val segment: Segment) : LiveOverlay
}

/** Everything a live surface needs; also used by the floating overlay window. */
class LiveUiModel(
    val live: LiveMeeting,
    val settings: AppSettings,
    val screenClass: ScreenSizeClass,
    val controller: MeetingController,
    val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val record get() = live.record
    val speakers = live.record.speakers.associateBy { it.id }
    val activeSpeaker = live.activeSpeakerId?.let { speakers[it] }

    fun captionStyle() = CaptionStyle(
        sizeSp = CaptionSizing.bar(screenClass, settings.captionSize),
        display = settings.translationDisplay,
        translationOn = live.translationEnabled,
        targets = live.targetLangs,
        showAvatars = settings.showAvatars,
        showTimestamps = settings.showTimestamps,
        showChannelBadges = live.record.dualChannel,
        wide = false,
        backdrop = 0f,
        bar = true,
    )

    fun level(): Float {
        val ch = activeSpeaker?.channel ?: Channel.LOCAL
        val lv = live.levels[ch] ?: 0f
        // The demo backend produces captions without audio; animate the wave while someone "speaks".
        return if (lv < 0.05f && live.activeSpeakerId != null) 0.55f else lv
    }
}

@Composable
fun rememberLiveUiModel(): LiveUiModel? {
    val ctx = LocalContext.current
    val container = ctx.container
    val live by container.meetingController.live.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val l = live ?: return null
    val screenClass = remember(settings.screenSize) {
        if (settings.screenSize == ScreenSizeClass.AUTO) CaptionSizing.detect(ctx) else settings.screenSize
    }
    return LiveUiModel(l, settings, screenClass, container.meetingController, container.settings::update)
}

// ------------------------------------------------------------------ status (S14)

@Composable
fun LinkBanner(link: LinkState, modifier: Modifier) {
    val (text, color, icon) = when (link) {
        LinkState.RECONNECTING -> Triple(stringResource(R.string.link_reconnecting), Palette.Warning, Icons.Outlined.CloudOff)
        LinkState.BACKFILLED -> Triple(stringResource(R.string.link_backfilled), Palette.Success, Icons.Outlined.CloudDone)
        LinkState.OFFLINE -> Triple(stringResource(R.string.link_offline), Palette.Danger, Icons.Outlined.CloudOff)
        else -> Triple(null, Palette.Text2, Icons.Outlined.CloudDone)
    }
    AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
        StatusPill(text.orEmpty(), color, icon = icon)
    }
}

/** Backend failure explained in one or two lines (e.g. SCH-900S unreachable, cloud key rejected). */
@Composable
fun ServiceErrorBanner(text: String?, modifier: Modifier) {
    AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
        Row(
            Modifier.widthIn(max = 1200.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Bg2.copy(alpha = 0.95f))
                .border(1.dp, Palette.Danger.copy(alpha = 0.6f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(text.orEmpty(), style = Type.Caption, color = Palette.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MicAlert(model: LiveUiModel, dismissed: Boolean, onDismiss: () -> Unit) {
    val mic = model.live.mic
    if (dismissed || mic == null || mic is MicStatus.Ok) return
    val (title, body) = when (mic) {
        MicStatus.NoPermission -> R.string.mic_no_permission_title to R.string.mic_no_permission_body
        MicStatus.NoDevice -> R.string.mic_no_device_title to R.string.mic_no_device_body
        is MicStatus.Failed -> R.string.mic_failed_title to R.string.mic_no_device_body
        else -> return
    }
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(880.dp).clip(RoundedCornerShape(32.dp)).background(Palette.Bg2).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.MicOff, null, tint = Palette.Warning, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(title), style = Type.TitleL)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(body), style = Type.Body, color = Palette.Text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SecondaryButton(stringResource(R.string.mic_continue_without), onDismiss)
                if (mic == MicStatus.NoPermission) {
                    PrimaryButton(stringResource(R.string.open_app_settings), {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    })
                } else {
                    PrimaryButton(stringResource(R.string.action_detect_audio), model.controller::retryMic)
                }
            }
        }
    }
}

@Composable
fun MarkFeedback(mark: Mark, onRetag: (MarkTag) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(mark.id, mark.tag) {
        delay(4_000)
        onDismiss()
    }
    Row(
        modifier.glass(36.dp, color = Palette.Bg2, alpha = 0.97f).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Flag, null, tint = Labels.tagColor(mark.tag), modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.marked_at, formatClock(mark.atMs)), style = Type.Label)
        Spacer(Modifier.width(24.dp))
        MarkTag.entries.forEach { tag ->
            Chip(stringResource(Labels.tag(tag)), mark.tag == tag, { onRetag(tag) }, height = 56.dp, icon = Labels.tagIcon(tag), accent = Labels.tagColor(tag))
            Spacer(Modifier.width(10.dp))
        }
        IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onDismiss, size = 64.dp)
    }
}

// ------------------------------------------------------------------ S08 end confirmation with undo countdown

@Composable
fun EndConfirmDialog(onContinue: () -> Unit, onEnd: () -> Unit) {
    var countdown by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c == 0) onEnd() else {
            delay(1_000)
            countdown = c - 1
        }
    }
    // Deliberately no tap-outside dismissal (§6.6).
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.75f)).pressable(onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(900.dp).clip(RoundedCornerShape(32.dp)).background(Palette.Bg2).border(1.dp, Palette.Stroke, RoundedCornerShape(32.dp)).padding(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.end_title), style = Type.TitleL)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.end_body), style = Type.Body, color = Palette.Text2)
            Spacer(Modifier.height(48.dp))
            val c = countdown
            if (c == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    SecondaryButton(stringResource(R.string.action_continue_meeting), onContinue, height = 96.dp)
                    DangerButton(stringResource(R.string.action_end_and_generate), { countdown = 3 })
                }
            } else {
                Text(stringResource(R.string.end_countdown, c), style = Type.TitleM.copy(fontWeight = FontWeight.SemiBold), color = Palette.Danger)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(stringResource(R.string.action_undo), { countdown = null }, height = 96.dp)
            }
        }
    }
}

/** Modal dialogs shared by the overlay window and the in-app fallback. */
@Composable
fun LiveDialogs(model: LiveUiModel, overlay: LiveOverlay?, onOverlay: (LiveOverlay?) -> Unit, onEnded: () -> Unit = {}) {
    when (val o = overlay) {
        LiveOverlay.More -> MoreSheet(model, onClose = { onOverlay(null) }, onShare = { onOverlay(LiveOverlay.Share) })
        LiveOverlay.Share -> ShareQrDialog(model.controller.liveShareUrl(model.record), model.record.code, onClose = { onOverlay(null) })
        LiveOverlay.End -> EndConfirmDialog(
            onContinue = { onOverlay(null) },
            onEnd = {
                model.controller.end()
                onOverlay(null)
                onEnded()
            },
        )
        is LiveOverlay.SegmentMenu -> SegmentActionMenu(
            segment = o.segment,
            speaker = model.speakers[o.segment.speakerId],
            onExcerpt = { tag ->
                model.controller.excerpt(o.segment.id, tag)
                onOverlay(null)
            },
            onDismiss = { onOverlay(null) },
        )
        null -> Unit
    }
}
