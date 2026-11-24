package com.aihuiji.meeting.ui.meeting

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.Mark
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.TranslationDisplay
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.ui.components.BigSwitch
import com.aihuiji.meeting.ui.components.BrandLogo
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

const val EDGE_L = 1
const val EDGE_T = 2
const val EDGE_R = 4
const val EDGE_B = 8
const val EDGE_ALL = EDGE_L or EDGE_T or EDGE_R or EDGE_B

/** Transparent ring around the bar that carries the resize grips; the visible surface is inset by this much. */
val BarRing = 24.dp
private val EdgeGrab = 34.dp
private val CornerGrab = 52.dp

/**
 * How the host window moves and resizes the bar. The overlay service implements all edges; the in-app
 * fallback only lets the user drag the top edge.
 */
class BarChrome(
    val edges: Int,
    val move: @Composable (onTap: (() -> Unit)?) -> Modifier,
    val resize: @Composable (mask: Int) -> Modifier,
)

class BarActions(
    val minimize: () -> Unit,
    val openMore: () -> Unit,
    val openShare: () -> Unit,
    val openEnd: () -> Unit,
    val segmentMenu: (Segment) -> Unit,
    val wantKeyboard: (Boolean) -> Unit,
)

internal enum class BarMenu { LANGUAGE, FONT }

@Composable
fun CaptionBarContent(model: LiveUiModel, actions: BarActions, chrome: BarChrome, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf<BarMenu?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var lastMark by remember { mutableStateOf<Mark?>(null) }
    LaunchedEffect(notesOpen) { actions.wantKeyboard(notesOpen) }
    DisposableEffect(Unit) { onDispose { actions.wantKeyboard(false) } }

    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(BarRing)
                .clip(shape)
                .background(Palette.Bg0.copy(alpha = 0.96f))
                .background(Palette.GlassTopHighlight)
                .border(1.5.dp, Color(0x40FFFFFF), shape),
        ) {
            BarToolbar(
                model = model,
                actions = actions,
                chrome = chrome,
                menu = menu,
                onMenu = { menu = if (menu == it) null else it },
                notesOpen = notesOpen,
                onNotes = { notesOpen = !notesOpen; menu = null },
                onMark = { lastMark = model.controller.addMark(MarkTag.KEY) },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Stroke))
            BarBody(
                model = model,
                actions = actions,
                menu = menu,
                onCloseMenu = { menu = null },
                notesOpen = notesOpen,
                onNotes = { notesOpen = !notesOpen },
                lastMark = lastMark,
                onMarkChange = { lastMark = it },
                modifier = Modifier.weight(1f),
            )
        }
        ResizeGrips(chrome)
    }
}

// ------------------------------------------------------------------ toolbar

@Composable
private fun BarToolbar(
    model: LiveUiModel,
    actions: BarActions,
    chrome: BarChrome,
    menu: BarMenu?,
    onMenu: (BarMenu) -> Unit,
    notesOpen: Boolean,
    onNotes: () -> Unit,
    onMark: () -> Unit,
) {
    val live = model.live
    val s = model.settings
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(88.dp)) {
        val full = maxWidth >= 1380.dp
        val medium = maxWidth >= 1000.dp
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().then(chrome.move(null)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandLogo(size = 52.dp)
                Spacer(Modifier.width(12.dp))
                if (medium) {
                    Text(stringResource(R.string.app_name), style = Type.Label.copy(fontSize = 28.sp), maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                }
                BarStatus(model, showLabel = full)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (live.isPaused) {
                    BarIcon(Icons.Outlined.PlayArrow, stringResource(R.string.action_resume), model.controller::resume, selected = true)
                } else {
                    BarIcon(Icons.Outlined.Pause, stringResource(R.string.action_pause), model.controller::pause)
                }
                BarIcon(Icons.Outlined.Flag, stringResource(R.string.action_mark), onMark)
                BarIcon(Icons.Outlined.EditNote, stringResource(R.string.action_notes), onNotes, selected = notesOpen)
                BarDivider()

                val targets = if (live.translationEnabled) live.targetLangs.joinToString("·") { it.tag } else ""
                BarPill(
                    icon = Icons.Outlined.Translate,
                    text = if (medium) stringResource(R.string.bar_language) + if (targets.isNotEmpty()) " · $targets" else "" else null,
                    onClick = { onMenu(BarMenu.LANGUAGE) },
                    active = menu == BarMenu.LANGUAGE,
                    dot = live.translationEnabled,
                )
                BarPill(
                    icon = Icons.Outlined.TextFields,
                    text = if (medium) stringResource(Labels.barFont(s.captionSize)) else null,
                    onClick = { onMenu(BarMenu.FONT) },
                    active = menu == BarMenu.FONT,
                )
                val canToggle = live.translationEnabled && live.targetLangs.isNotEmpty()
                val showOriginal = s.translationDisplay != TranslationDisplay.TRANSLATION_ONLY || !canToggle
                BarPill(
                    icon = if (showOriginal) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                    text = if (medium) stringResource(if (showOriginal) R.string.bar_hide_original else R.string.bar_show_original) else null,
                    onClick = {
                        model.updateSettings {
                            it.copy(translationDisplay = if (showOriginal) TranslationDisplay.TRANSLATION_ONLY else TranslationDisplay.INTERLEAVED)
                        }
                    },
                    enabled = canToggle,
                    chevron = false,
                )
                BarDivider()
                BarIcon(Icons.Outlined.Tune, stringResource(R.string.action_more), actions.openMore)
                BarIcon(Icons.Outlined.Remove, stringResource(R.string.action_minimize), actions.minimize)
                BarIcon(Icons.Outlined.Close, stringResource(R.string.action_end_meeting), actions.openEnd, tint = Palette.Danger)
            }
        }
    }
}

@Composable
private fun BarStatus(model: LiveUiModel, showLabel: Boolean) {
    val paused = model.live.isPaused
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "rec",
    )
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Bg2.copy(alpha = 0.85f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).alpha(if (paused) 1f else blink).background(if (paused) Palette.Warning else Palette.Danger))
        if (showLabel) {
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (paused) R.string.state_paused else R.string.state_recording), style = Type.Label.copy(fontSize = 22.sp), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            formatClock(model.live.elapsedMs),
            style = Type.Label.copy(fontSize = 22.sp, fontFeatureSettings = "tnum"),
            color = Palette.Text2,
            maxLines = 1,
        )
    }
}

@Composable
internal fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, selected: Boolean = false, tint: Color = Palette.Text1) {
    Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Palette.Brand500.copy(alpha = 0.85f)) else Modifier)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun BarPill(
    icon: ImageVector,
    text: String?,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = true,
    dot: Boolean = false,
) {
    Row(
        Modifier
            .height(60.dp)
            .clip(CircleShape)
            .then(if (active) Modifier.background(Palette.Brand500.copy(alpha = 0.35f)) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(icon, null, tint = Palette.Text1, modifier = Modifier.size(30.dp))
            if (dot) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(Palette.Success))
        }
        if (text != null) {
            Spacer(Modifier.width(8.dp))
            Text(text, style = Type.Label.copy(fontSize = 24.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        if (chevron) Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Palette.Text2, modifier = Modifier.size(26.dp))
    }
}

@Composable
internal fun BarDivider() {
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(Palette.Stroke))
}

// ------------------------------------------------------------------ body

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarBody(
    model: LiveUiModel,
    actions: BarActions,
    menu: BarMenu?,
    onCloseMenu: () -> Unit,
    notesOpen: Boolean,
    onNotes: () -> Unit,
    lastMark: Mark?,
    onMarkChange: (Mark?) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bodyW = maxWidth
        val bodyH = maxHeight
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CaptionList(
                    segments = model.record.segments,
                    speakers = model.speakers,
                    activeSpeakerId = model.live.activeSpeakerId,
                    style = model.captionStyle(),
                    onLongPress = actions.segmentMenu,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                    empty = { BarListening(model) },
                )
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp)
                        .background(Brush.verticalGradient(listOf(Palette.Bg0.copy(alpha = 0.96f), Color.Transparent))),
                )
                LinkBanner(model.live.link, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                lastMark?.let { mark ->
                    MarkFeedback(
                        mark = mark,
                        onRetag = { tag -> model.controller.retagMark(mark.id, tag); onMarkChange(mark.copy(tag = tag)) },
                        onDismiss = { onMarkChange(null) },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    )
                }
            }
            if (notesOpen) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Stroke))
                NotesPanel(
                    model = model,
                    modifier = Modifier.width(minOf(560.dp, bodyW * 0.46f)).fillMaxHeight().padding(20.dp),
                    onClose = onNotes,
                )
            }
        }
        if (menu != null) {
            Box(
                Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onCloseMenu() } },
            )
            val menuShape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .widthIn(max = minOf(780.dp, bodyW - 24.dp))
                    .heightIn(max = bodyH - 24.dp)
                    .clip(menuShape)
                    .background(Palette.Bg2)
                    .border(1.dp, Palette.Stroke, menuShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                when (menu) {
                    BarMenu.LANGUAGE -> LanguageMenu(model)
                    BarMenu.FONT -> FontMenu(model)
                }
            }
        }
    }
}

@Composable
private fun BarListening(model: LiveUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Waveform(level = if (model.live.isPaused) 0f else 0.4f, maxHeight = 56.dp, barWidth = 9.dp, gap = 7.dp)
        Spacer(Modifier.width(24.dp))
        Text(
            stringResource(if (model.live.isPaused) R.string.paused_hint else R.string.listening_hint),
            style = Type.TitleM,
            color = Palette.Text2,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageMenu(model: LiveUiModel) {
    val live = model.live
    val primary = live.record.primaryLang
    val fallbackTarget = if (primary == Lang.EN) Lang.ZH else Lang.EN
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.more_translation), style = Type.Label)
            Spacer(Modifier.width(20.dp))
            BigSwitch(live.translationEnabled, { model.controller.setTranslation(it, live.targetLangs.ifEmpty { listOf(fallbackTarget) }) })
            Spacer(Modifier.width(32.dp))
            Text(
                stringResource(R.string.bar_recognition, if (live.record.autoDetect) stringResource(R.string.lang_auto) else primary.nativeName),
                style = Type.Caption,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.more_target_lang), style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Lang.entries.filter { it != primary }.forEach { lang ->
                val selected = lang in live.targetLangs
                Chip(lang.nativeName, selected && live.translationEnabled, {
                    val next = if (selected) live.targetLangs - lang else (live.targetLangs + lang).takeLast(2)
                    model.controller.setTranslation(live.translationEnabled || !selected, next)
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontMenu(model: LiveUiModel) {
    Column {
        Text(stringResource(R.string.more_font_size), style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CaptionSize.entries.forEach { size ->
                Chip(stringResource(Labels.size(size)), model.settings.captionSize == size, { model.updateSettings { it.copy(captionSize = size) } })
            }
        }
    }
}

// ------------------------------------------------------------------ resize grips

@Composable
internal fun ResizeGrips(chrome: BarChrome) {
    val e = chrome.edges
    val l = e and EDGE_L != 0
    val t = e and EDGE_T != 0
    val r = e and EDGE_R != 0
    val b = e and EDGE_B != 0
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = BarRing.toPx() / 2f
            val stroke = 5.dp.toPx()
            val color = Color(0x70FFFFFF)
            val len = 72.dp.toPx()
            val arc = 40.dp.toPx()
            val w = size.width
            val h = size.height
            if (l) drawLine(color, Offset(mid, h / 2 - len / 2), Offset(mid, h / 2 + len / 2), stroke, StrokeCap.Round)
            if (r) drawLine(color, Offset(w - mid, h / 2 - len / 2), Offset(w - mid, h / 2 + len / 2), stroke, StrokeCap.Round)
            if (t) drawLine(color, Offset(w / 2 - len / 2, mid), Offset(w / 2 + len / 2, mid), stroke, StrokeCap.Round)
            if (b) drawLine(color, Offset(w / 2 - len / 2, h - mid), Offset(w / 2 + len / 2, h - mid), stroke, StrokeCap.Round)
            val box = Size(arc * 2, arc * 2)
            val st = Stroke(width = stroke, cap = StrokeCap.Round)
            if (l && t) drawArc(color, 180f, 90f, false, Offset(mid, mid), box, style = st)
            if (r && t) drawArc(color, 270f, 90f, false, Offset(w - mid - box.width, mid), box, style = st)
            if (r && b) drawArc(color, 0f, 90f, false, Offset(w - mid - box.width, h - mid - box.height), box, style = st)
            if (l && b) drawArc(color, 90f, 90f, false, Offset(mid, h - mid - box.height), box, style = st)
        }
        val padH = if (l || r) CornerGrab else 0.dp
        val padV = if (t || b) CornerGrab else 0.dp
        if (l) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EdgeGrab).padding(vertical = padV).then(chrome.resize(EDGE_L)))
        if (r) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EdgeGrab).padding(vertical = padV).then(chrome.resize(EDGE_R)))
        if (t) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EdgeGrab).padding(horizontal = padH).then(chrome.resize(EDGE_T)))
        if (b) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EdgeGrab).padding(horizontal = padH).then(chrome.resize(EDGE_B)))
        if (l && t) Box(Modifier.align(Alignment.TopStart).size(CornerGrab).then(chrome.resize(EDGE_L or EDGE_T)))
        if (r && t) Box(Modifier.align(Alignment.TopEnd).size(CornerGrab).then(chrome.resize(EDGE_R or EDGE_T)))
        if (l && b) Box(Modifier.align(Alignment.BottomStart).size(CornerGrab).then(chrome.resize(EDGE_L or EDGE_B)))
        if (r && b) Box(Modifier.align(Alignment.BottomEnd).size(CornerGrab).then(chrome.resize(EDGE_R or EDGE_B)))
    }
}
