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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EditNote
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

    fun captionStyle(panel: Boolean) = CaptionStyle(
        sizeSp = if (panel) CaptionSizing.panel(screenClass, settings.captionSize) else CaptionSizing.inRoom(screenClass, settings.captionSize),
        display = settings.translationDisplay,
        translationOn = live.translationEnabled,
        targets = live.targetLangs,
        showAvatars = settings.showAvatars,
        showTimestamps = settings.showTimestamps,
        showChannelBadges = live.record.dualChannel,
        wide = !panel,
        backdrop = settings.captionBackdrop,
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

@Composable
fun LiveMeetingScreen() {
    val model = rememberLiveUiModel() ?: return
    var overlay by remember { mutableStateOf<LiveOverlay?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var lastMark by remember { mutableStateOf<Mark?>(null) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    BackHandler { overlay = LiveOverlay.End }

    val onMark: () -> Unit = { lastMark = model.controller.addMark(MarkTag.KEY) }
    val actions = LiveActions(
        openMore = { overlay = LiveOverlay.More },
        openShare = { overlay = LiveOverlay.Share },
        openEnd = { overlay = LiveOverlay.End },
        toggleNotes = { notesOpen = !notesOpen },
        mark = onMark,
        segmentMenu = { overlay = LiveOverlay.SegmentMenu(it) },
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg0)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = System.currentTimeMillis()
                    }
                }
            },
    ) {
        EdgeGuard(Modifier.fillMaxSize()) {
            if (model.live.presentation == MeetingMode.IN_ROOM) {
                InRoomLayout(model, actions, notesOpen, lastTouch)
            } else {
                VideoSplitLayout(model, actions, notesOpen)
            }
        }

        LinkBanner(model.live.link, Modifier.align(Alignment.TopCenter).padding(top = 120.dp))
        MicAlert(model)

        lastMark?.let { mark ->
            MarkFeedback(
                mark = mark,
                onRetag = { tag -> model.controller.retagMark(mark.id, tag); lastMark = mark.copy(tag = tag) },
                onDismiss = { lastMark = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp),
            )
        }

        when (val o = overlay) {
            LiveOverlay.More -> MoreSheet(model, onClose = { overlay = null }, onShare = { overlay = LiveOverlay.Share })
            LiveOverlay.Share -> ShareQrDialog(model.controller.liveShareUrl(model.record), model.record.code, onClose = { overlay = null })
            LiveOverlay.End -> EndConfirmDialog(
                onContinue = { overlay = null },
                onEnd = { model.controller.end() },
            )
            is LiveOverlay.SegmentMenu -> SegmentActionMenu(
                segment = o.segment,
                speaker = model.speakers[o.segment.speakerId],
                onExcerpt = { tag ->
                    model.controller.excerpt(o.segment.id, tag)
                    overlay = null
                },
                onDismiss = { overlay = null },
            )
            null -> Unit
        }
    }
}

class LiveActions(
    val openMore: () -> Unit,
    val openShare: () -> Unit,
    val openEnd: () -> Unit,
    val toggleNotes: () -> Unit,
    val mark: () -> Unit,
    val segmentMenu: (Segment) -> Unit,
)

// ------------------------------------------------------------------ S04 in-room full-screen captions

@Composable
private fun InRoomLayout(model: LiveUiModel, actions: LiveActions, notesOpen: Boolean, lastTouch: Long) {
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch) {
        idle = false
        delay(10_000)
        idle = true
    }
    val toolbarAlpha by animateFloatAsState(if (idle) 0.5f else 1f, tween(240), label = "idle")

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        LiveTopBar(model, actions)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            CaptionList(
                segments = model.record.segments,
                speakers = model.speakers,
                activeSpeakerId = model.live.activeSpeakerId,
                style = model.captionStyle(panel = false),
                onLongPress = actions.segmentMenu,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp),
                contentPadding = PaddingValues(vertical = 32.dp),
                empty = { ListeningPlaceholder(model) },
            )
            AnimatedVisibility(notesOpen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                NotesPanel(
                    model = model,
                    modifier = Modifier.width(560.dp).fillMaxHeight().glass(32.dp).padding(28.dp),
                    onClose = actions.toggleNotes,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(120.dp).glass(32.dp).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).alpha(toolbarAlpha), verticalAlignment = Alignment.CenterVertically) {
                CurrentSpeaker(model)
                Spacer(Modifier.weight(1f))
                PauseAction(model)
                Spacer(Modifier.width(16.dp))
                ToolAction(Icons.Outlined.Flag, stringResource(R.string.action_mark), actions.mark)
                Spacer(Modifier.width(16.dp))
                ToolAction(Icons.Outlined.EditNote, stringResource(R.string.action_notes), actions.toggleNotes, highlighted = notesOpen)
                Spacer(Modifier.width(16.dp))
                ToolAction(Icons.Outlined.MoreHoriz, stringResource(R.string.action_more), actions.openMore)
            }
            Spacer(Modifier.width(40.dp))
            DangerButton(stringResource(R.string.action_end_meeting), actions.openEnd, icon = Icons.Outlined.StopCircle)
        }
    }
}

@Composable
private fun ListeningPlaceholder(model: LiveUiModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Waveform(level = 0.4f, maxHeight = 96.dp, barWidth = 14.dp, gap = 10.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (model.live.isPaused) R.string.paused_hint else R.string.listening_hint),
            style = Type.TitleM,
            color = Palette.Text2,
        )
    }
}

@Composable
private fun LiveTopBar(model: LiveUiModel, actions: LiveActions) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordingPill(model)
        Spacer(Modifier.width(16.dp))
        StatusPill(
            text = stringResource(R.string.meeting_code_value, model.record.code),
            color = Palette.Brand400,
            icon = Icons.Outlined.QrCode2,
            onClick = actions.openShare,
        )
        Spacer(Modifier.width(16.dp))
        ProfilePill(model)
        Spacer(Modifier.weight(1f))
        LanguagePill(model, actions.openMore)
        Spacer(Modifier.width(16.dp))
        IconCircleButton(Icons.Outlined.TextIncrease, stringResource(R.string.action_font_size), {
            model.updateSettings { s -> s.copy(captionSize = CaptionSize.entries[(s.captionSize.ordinal + 1) % CaptionSize.entries.size]) }
        }, size = 72.dp)
        Spacer(Modifier.width(16.dp))
        IconCircleButton(Icons.Outlined.MoreHoriz, stringResource(R.string.action_more), actions.openMore, size = 72.dp)
    }
}

@Composable
fun RecordingPill(model: LiveUiModel, compact: Boolean = false) {
    val paused = model.live.isPaused
    Row(
        Modifier.height(if (compact) 48.dp else 56.dp).glass(28.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val blink by rememberInfiniteTransition(label = "rec").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "rec",
        )
        Box(Modifier.size(16.dp).clip(CircleShape).alpha(if (paused) 1f else blink).background(if (paused) Palette.Warning else Palette.Danger))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(if (paused) R.string.state_paused else R.string.state_recording),
            style = Type.Label.copy(fontSize = if (compact) 22.sp else 26.sp),
        )
        Spacer(Modifier.width(12.dp))
        Text(formatClock(model.live.elapsedMs), style = Type.Label.copy(fontSize = if (compact) 22.sp else 26.sp, fontFeatureSettings = "tnum"), color = Palette.Text2)
    }
}

@Composable
private fun ProfilePill(model: LiveUiModel) {
    val profile = model.settings.profiles.firstOrNull { it.id == model.record.profileId } ?: model.settings.activeProfile
    val name = stringResource(Labels.profile(profile.kind))
    val demo = model.live.location == com.aihuiji.meeting.core.provider.ProviderLocation.DEMO
    StatusPill(
        text = if (demo) stringResource(R.string.profile_demo_suffix, name) else name,
        color = if (model.live.link == LinkState.CONNECTED || model.live.link == LinkState.BACKFILLED) Palette.Success else Palette.Warning,
    )
}

@Composable
private fun LanguagePill(model: LiveUiModel, onClick: () -> Unit) {
    val primary = if (model.record.autoDetect) stringResource(R.string.lang_auto) else model.record.primaryLang.nativeName
    val text = if (model.live.translationEnabled && model.live.targetLangs.isNotEmpty()) {
        "$primary → ${model.live.targetLangs.joinToString(" · ") { it.tag }}"
    } else primary
    StatusPill(text = text, color = Palette.Brand400, icon = Icons.Outlined.Translate, onClick = onClick)
}

@Composable
private fun CurrentSpeaker(model: LiveUiModel, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Text(stringResource(R.string.current_speaker), style = Type.Caption)
            Spacer(Modifier.width(16.dp))
        }
        val sp = model.activeSpeaker
        if (sp != null) {
            SpeakerAvatar(sp, size = if (compact) 40.dp else 56.dp, active = true, showChannelBadge = model.record.dualChannel)
            Spacer(Modifier.width(12.dp))
            Text(Labels.speakerName(sp), style = Type.Label)
        } else {
            Text("—", style = Type.Label, color = Palette.Text2)
        }
        Spacer(Modifier.width(20.dp))
        Waveform(level = model.level(), maxHeight = if (compact) 40.dp else 64.dp, barWidth = if (compact) 6.dp else 8.dp)
    }
}

@Composable
private fun PauseAction(model: LiveUiModel) {
    if (model.live.isPaused) {
        ToolAction(Icons.Outlined.PlayArrow, stringResource(R.string.action_resume), model.controller::resume, highlighted = true)
    } else {
        ToolAction(Icons.Outlined.Pause, stringResource(R.string.action_pause), model.controller::pause)
    }
}

// ------------------------------------------------------------------ S05 video meeting (split layout)

@Composable
private fun VideoSplitLayout(model: LiveUiModel, actions: LiveActions, notesOpen: Boolean) {
    val ctx = LocalContext.current
    LocalConfiguration.current
    val inMultiWindow = (ctx as? Activity)?.isInMultiWindowMode == true
    if (inMultiWindow) {
        MeetingPanel(model, actions, Modifier.fillMaxSize().padding(24.dp))
        return
    }
    var ratio by remember { mutableFloatStateOf(0.65f) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val total = maxWidth
        Row(Modifier.fillMaxSize()) {
            VcPlaceholder(model, Modifier.width(total * ratio - 12.dp).fillMaxHeight())
            Box(
                Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dx ->
                            ratio = (ratio + dx / total.toPx()).coerceIn(0.60f, 0.75f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(6.dp).height(120.dp).clip(CircleShape).background(Palette.Text2.copy(alpha = 0.5f)))
            }
            MeetingPanel(model, actions, Modifier.weight(1f).fillMaxHeight(), notesTab = notesOpen)
        }
    }
}

/** Stand-in for the conference app's half of the split when the app runs full-screen (no multi-window). */
@Composable
private fun VcPlaceholder(model: LiveUiModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val app = remember(model.settings.vcAppPackage) { VcLauncher.installedApp(ctx, model.settings.vcAppPackage) }
    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Palette.Bg1.copy(alpha = 0.6f))
            .border(2.dp, Palette.Stroke, RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            Icon(Icons.Outlined.Videocam, null, tint = Palette.Text2, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.vc_area_title), style = Type.TitleL, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (app != null) R.string.vc_area_hint else R.string.vc_area_not_installed),
                style = Type.Body,
                color = Palette.Text2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            if (app != null) {
                PrimaryButton(
                    stringResource(R.string.vc_open_app, app.label),
                    { VcLauncher.launchAdjacent(ctx, app.packageName) },
                    icon = Icons.Outlined.OpenInNew,
                )
            }
        }
    }
}

/** AI panel content shared by split (S05-A) and floating (S05-B) layouts. */
@Composable
fun MeetingPanel(
    model: LiveUiModel,
    actions: LiveActions,
    modifier: Modifier = Modifier,
    notesTab: Boolean = false,
    floating: Boolean = false,
    header: @Composable () -> Unit = {},
) {
    var tab by remember(notesTab) { mutableStateOf(if (notesTab) 1 else 0) }
    Column(modifier.glass(32.dp, alpha = if (floating) 0.92f else 0.88f).padding(24.dp)) {
        header()
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = Type.Label)
            Spacer(Modifier.weight(1f))
            StatusPill(model.record.code, Palette.Brand400, icon = Icons.Outlined.QrCode2, onClick = actions.openShare)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingPill(model, compact = true)
            Spacer(Modifier.weight(1f))
            if (!floating) {
                IconCircleButton(Icons.Outlined.SwapHoriz, stringResource(R.string.action_switch_layout), {
                    model.controller.setLayout(com.aihuiji.meeting.core.model.PanelLayout.FLOATING)
                }, size = 64.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (model.live.far == FarStatus.UNAVAILABLE) {
            StatusPill(stringResource(R.string.near_only_notice), Palette.Warning)
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(stringResource(R.string.tab_captions), tab == 0, { tab = 0 }, height = 52.dp)
            Chip(stringResource(R.string.tab_notes_short), tab == 1, { tab = 1 }, height = 52.dp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                CaptionList(
                    segments = model.record.segments,
                    speakers = model.speakers,
                    activeSpeakerId = model.live.activeSpeakerId,
                    style = model.captionStyle(panel = true),
                    onLongPress = actions.segmentMenu,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    empty = { Text(stringResource(R.string.listening_hint), style = Type.Body, color = Palette.Text2) },
                )
            } else {
                NotesPanel(model, Modifier.fillMaxSize(), onClose = null)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val paused = model.live.isPaused
            IconCircleButton(
                if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                if (paused) model.controller::resume else model.controller::pause,
                size = 80.dp,
                selected = paused,
            )
            IconCircleButton(Icons.Outlined.Flag, stringResource(R.string.action_mark), actions.mark, size = 80.dp)
            IconCircleButton(Icons.Outlined.EditNote, stringResource(R.string.action_notes), { tab = if (tab == 1) 0 else 1 }, size = 80.dp, selected = tab == 1)
            IconCircleButton(Icons.Outlined.MoreHoriz, stringResource(R.string.action_more), actions.openMore, size = 80.dp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(Palette.Danger).pressable(onClick = actions.openEnd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.StopCircle, stringResource(R.string.action_end_meeting), tint = Palette.Text1, modifier = Modifier.size(44.dp)) }
        }
    }
}

// ------------------------------------------------------------------ status (S14)

@Composable
private fun LinkBanner(link: LinkState, modifier: Modifier) {
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

@Composable
private fun MicAlert(model: LiveUiModel) {
    val mic = model.live.mic
    var dismissed by remember(mic) { mutableStateOf(false) }
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
                SecondaryButton(stringResource(R.string.mic_continue_without), { dismissed = true })
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
private fun MarkFeedback(mark: Mark, onRetag: (MarkTag) -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
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
