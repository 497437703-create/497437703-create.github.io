package com.aihuiji.meeting.ui.meeting

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.ScreenSizeClass
import com.aihuiji.meeting.core.model.Segment
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.core.model.TranslationDisplay
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.ui.components.SpeakerAvatar
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class CaptionStyle(
    val sizeSp: Int,
    val display: TranslationDisplay,
    val translationOn: Boolean,
    val targets: List<Lang>,
    val showAvatars: Boolean,
    val showTimestamps: Boolean,
    val showChannelBadges: Boolean,
    val wide: Boolean,
    val backdrop: Float,
    val bar: Boolean = false,
)

object CaptionSizing {
    /** Picks the machine class from the panel's physical diagonal; falls back to the 86" defaults. */
    fun detect(context: Context): ScreenSizeClass {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels / dm.xdpi
        val h = dm.heightPixels / dm.ydpi
        val diag = sqrt(w * w + h * h)
        if (diag < 55) return ScreenSizeClass.AUTO
        return listOf(ScreenSizeClass.IN65, ScreenSizeClass.IN75, ScreenSizeClass.IN86, ScreenSizeClass.IN98)
            .minBy { kotlin.math.abs(it.inches - diag) }
    }

    fun inRoom(base: ScreenSizeClass, size: CaptionSize): Int =
        (base.inRoomCaptionSp + step(size) * 8).coerceIn(40, 72)

    fun panel(base: ScreenSizeClass, size: CaptionSize): Int =
        (base.panelCaptionSp + step(size) * 4).coerceIn(26, 40)

    fun bar(base: ScreenSizeClass, size: CaptionSize): Int =
        (base.panelCaptionSp + 8 + step(size) * 6).coerceIn(30, 64)

    private fun step(size: CaptionSize) = when (size) {
        CaptionSize.S -> -1
        CaptionSize.M -> 0
        CaptionSize.L -> 1
        CaptionSize.XL -> 2
    }
}

/**
 * Live caption stream. Follows the newest segment; when the user scrolls back it stops
 * following and returns automatically after 5 s of inactivity (§6.2), with a 回到最新 pill.
 */
@Composable
fun CaptionList(
    segments: List<Segment>,
    speakers: Map<String, Speaker>,
    activeSpeakerId: String?,
    style: CaptionStyle,
    onLongPress: (Segment) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 24.dp),
    empty: @Composable () -> Unit = {},
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    // reverseLayout: index 0 is the newest caption, anchored to the bottom edge, so a growing
    // partial pushes older lines up without any scroll arithmetic.
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 8 } }
    // Only a finger drag pauses auto-follow; our own animateScrollToItem must not.
    LaunchedEffect(state) {
        state.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    if (!atBottom) delay(5_000)
                    following = true
                }
            }
        }
    }
    LaunchedEffect(segments.size, following) {
        if (following && segments.isNotEmpty()) state.animateScrollToItem(0)
    }
    val newestFirst = remember(segments) { segments.asReversed() }

    Box(modifier) {
        if (segments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { empty() }
        }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(if (style.bar) 12.dp else if (style.wide) 32.dp else 22.dp, Alignment.Bottom),
        ) {
            items(newestFirst, key = { it.id }) { seg ->
                CaptionBlock(
                    segment = seg,
                    speaker = speakers[seg.speakerId],
                    active = seg.speakerId == activeSpeakerId && !seg.isFinal,
                    style = style,
                    onLongPress = { onLongPress(seg) },
                    modifier = Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(180), fadeOutSpec = null),
                )
            }
        }
        AnimatedVisibility(
            visible = !following,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            StatusPill(
                text = androidx.compose.ui.res.stringResource(R.string.back_to_latest),
                color = Palette.Brand400,
                icon = Icons.Outlined.ArrowDownward,
                onClick = {
                    following = true
                    scope.launch { if (segments.isNotEmpty()) state.animateScrollToItem(0) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CaptionBlock(
    segment: Segment,
    speaker: Speaker?,
    active: Boolean,
    style: CaptionStyle,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (style.bar) {
        BarCaptionBlock(segment, speaker, style, onLongPress, modifier)
        return
    }
    val avatar: Dp = if (style.wide) 64.dp else 52.dp
    val textStyle = Type.Body.copy(
        fontSize = style.sizeSp.sp,
        lineHeight = (style.sizeSp * if (style.wide) 1.45f else 1.5f).sp,
        fontWeight = if (style.wide) FontWeight.Medium else FontWeight.Normal,
        lineBreak = if (segment.lang.isCjk) LineBreak.Paragraph else LineBreak.Paragraph,
        hyphens = if (segment.lang == Lang.DE || segment.lang == Lang.FR) Hyphens.Auto else Hyphens.None,
    )
    val translations = if (style.translationOn) style.targets.filter { it != segment.lang } else emptyList()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .then(if (style.backdrop > 0f) Modifier.background(Palette.Bg0.copy(alpha = style.backdrop)) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .animateContentSize(tween(120)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (style.showAvatars) {
                SpeakerAvatar(speaker, size = avatar, active = active, showChannelBadge = style.showChannelBadges)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                Labels.speakerName(speaker),
                style = Type.TitleM.copy(fontSize = if (style.wide) 30.sp else 26.sp),
                color = Palette.speaker(speaker?.colorIndex ?: 0).let { c -> if (style.wide) Palette.Text1 else c },
            )
            Spacer(Modifier.weight(1f))
            if (style.showTimestamps) {
                Text(formatClock(segment.startMs), style = Type.Caption.copy(fontFeatureSettings = "tnum"))
            }
        }
        Spacer(Modifier.height(8.dp))
        val showOriginal = style.display != TranslationDisplay.TRANSLATION_ONLY || translations.isEmpty()
        if (style.display == TranslationDisplay.BLOCK && style.wide && translations.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                OriginalText(segment, textStyle, Modifier.weight(1f))
                Column(Modifier.weight(1f)) { translations.forEach { TranslationLine(segment, it, textStyle, block = true) } }
            }
        } else {
            if (showOriginal) OriginalText(segment, textStyle)
            translations.forEach { lang ->
                Spacer(Modifier.height(6.dp))
                TranslationLine(segment, lang, textStyle, block = style.display == TranslationDisplay.BLOCK, primary = !showOriginal)
            }
        }
    }
}

@Composable
private fun OriginalText(segment: Segment, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        if (segment.isFinal) {
            append(segment.text)
        } else {
            withStyle(SpanStyle(color = Palette.Text2)) { append(segment.text) }
            withStyle(SpanStyle(color = Palette.Brand400)) { append("▍") }
        }
    }
    Text(text, style = style, color = Palette.Text1, modifier = modifier.padding(start = 4.dp))
}

@Composable
private fun TranslationLine(segment: Segment, lang: Lang, base: androidx.compose.ui.text.TextStyle, block: Boolean, primary: Boolean = false) {
    val text = segment.translations[lang]
    val size = if (primary) base.fontSize else (base.fontSize.value - 2).sp
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (block) Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Brand700.copy(alpha = 0.18f)) else Modifier)
            .padding(vertical = if (block) 10.dp else 0.dp),
    ) {
        Box(Modifier.width(4.dp).height((size.value * 1.4f).dp).clip(RoundedCornerShape(2.dp)).background(Palette.Brand500))
        Spacer(Modifier.width(14.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.TextTrans.copy(alpha = 0.8f))) { append(lang.tag + "  ") }
                if (text != null) append(text)
                else withStyle(SpanStyle(color = Palette.TextTrans.copy(alpha = 0.45f))) {
                    append(androidx.compose.ui.res.stringResource(R.string.translation_pending))
                }
            },
            style = base.copy(fontSize = size, lineHeight = (size.value * 1.5f).sp),
            color = Palette.TextTrans,
        )
    }
}

/** Long-press menu on a caption: 摘录 · 标记为待办 · 决议 · 疑问 (§6.9). */
@Composable
fun SegmentActionMenu(
    segment: Segment,
    speaker: Speaker?,
    onExcerpt: (MarkTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg0.copy(alpha = 0.55f))
            .pressable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(760.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Palette.Bg2)
                .pressable(onClick = {})
                .padding(32.dp),
        ) {
            Text("${Labels.speakerName(speaker)} · ${formatClock(segment.startMs)}", style = Type.Caption)
            Spacer(Modifier.height(8.dp))
            Text(segment.text, style = Type.Body, maxLines = 4)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                com.aihuiji.meeting.ui.components.ToolAction(
                    androidx.compose.material.icons.Icons.Outlined.ContentCut,
                    androidx.compose.ui.res.stringResource(R.string.action_excerpt),
                    { onExcerpt(null) },
                    highlighted = true,
                )
                listOf(MarkTag.TODO, MarkTag.DECISION, MarkTag.QUESTION).forEach { tag ->
                    com.aihuiji.meeting.ui.components.ToolAction(
                        Labels.tagIcon(tag),
                        androidx.compose.ui.res.stringResource(R.string.mark_as, androidx.compose.ui.res.stringResource(Labels.tag(tag))),
                        { onExcerpt(tag) },
                        tint = Labels.tagColor(tag),
                    )
                }
            }
        }
    }
}


/** Compact caption paragraph for the bottom caption bar: speaker tag inline, translation in blue underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarCaptionBlock(
    segment: Segment,
    speaker: Speaker?,
    style: CaptionStyle,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = style.sizeSp.toFloat()
    val translations = if (style.translationOn) style.targets.filter { it != segment.lang } else emptyList()
    val showOriginal = style.display != TranslationDisplay.TRANSLATION_ONLY || translations.isEmpty()
    val remoteShort = androidx.compose.ui.res.stringResource(R.string.channel_remote_short)
    val pending = androidx.compose.ui.res.stringResource(R.string.translation_pending)
    val speakerColor = Palette.speaker(speaker?.colorIndex ?: 0)
    val name = Labels.speakerName(speaker)
    val remote = style.showChannelBadges && speaker?.channel == com.aihuiji.meeting.core.model.Channel.REMOTE

    fun androidx.compose.ui.text.AnnotatedString.Builder.header() {
        if (style.showAvatars) {
            withStyle(SpanStyle(color = speakerColor, fontWeight = FontWeight.Bold, fontSize = (size * 0.62f).sp)) {
                append(if (remote) "$name · $remoteShort" else name)
                append("   ")
            }
        }
        if (style.showTimestamps) {
            withStyle(SpanStyle(color = Palette.Text2, fontSize = (size * 0.5f).sp)) { append(formatClock(segment.startMs) + "   ") }
        }
    }

    val base = Type.Body.copy(fontSize = size.sp, lineHeight = (size * 1.38f).sp, fontWeight = FontWeight.Medium)
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(vertical = 2.dp)
            .animateContentSize(tween(120)),
    ) {
        if (showOriginal) {
            Text(
                buildAnnotatedString {
                    header()
                    if (segment.isFinal) append(segment.text) else {
                        withStyle(SpanStyle(color = Palette.Text2)) { append(segment.text) }
                        withStyle(SpanStyle(color = Palette.Brand400)) { append("▍") }
                    }
                },
                style = base,
                color = Palette.Text1,
            )
        }
        translations.forEachIndexed { index, lang ->
            val text = segment.translations[lang]
            Text(
                buildAnnotatedString {
                    if (!showOriginal && index == 0) header()
                    withStyle(SpanStyle(fontSize = (size * 0.46f).sp, fontWeight = FontWeight.Bold, color = Palette.BarTrans.copy(alpha = 0.7f))) { append(lang.tag + "  ") }
                    if (text != null) append(text) else withStyle(SpanStyle(color = Palette.BarTrans.copy(alpha = 0.45f))) { append(pending) }
                },
                style = base.copy(fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.38f).sp, fontWeight = FontWeight.Normal),
                color = Palette.BarTrans,
            )
        }
    }
}
