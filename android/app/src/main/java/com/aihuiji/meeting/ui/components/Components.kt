package com.aihuiji.meeting.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.model.Speaker
import com.aihuiji.meeting.ui.theme.LocalUiPrefs
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

/** Glass surface without runtime blur (G1 / Mali G52: §4.2). */
fun Modifier.glass(radius: Dp = 24.dp, alpha: Float = 0.88f, color: Color = Palette.Bg1): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(color.copy(alpha = alpha))
        .background(Palette.GlassTopHighlight)
        .border(1.dp, Palette.Stroke, shape)
}

/** Press feedback within 80 ms: brighten + scale 0.97 (§4.4). No hover dependency. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(80), label = "press")
    return this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
        .drawBehind { if (pressed) drawRect(Color(0x22FFFFFF)) }
}

@Composable
private fun ButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    background: Modifier,
    height: Dp,
    icon: ImageVector?,
    enabled: Boolean,
    contentColor: Color = Color.White,
) {
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .then(background)
            .pressable(enabled, onClick = onClick)
            .padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = Type.TitleM.copy(fontSize = 30.sp), color = contentColor.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 88.dp) =
    ButtonBase(text, onClick, modifier, Modifier.background(if (enabled) Palette.BrandGradient else Brush.linearGradient(listOf(Palette.Bg2, Palette.Bg2))), height, icon, enabled)

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 88.dp) =
    ButtonBase(text, onClick, modifier, Modifier.glass(radius = 44.dp), height, icon, enabled, Palette.Text1)

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 96.dp) =
    ButtonBase(text, onClick, modifier, Modifier.background(Palette.Danger), height, icon, true)

@Composable
fun IconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Palette.BrandGradient) else Modifier.glass(radius = size / 2))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Palette.Text1, modifier = Modifier.size(size * 0.45f))
    }
}

/** Compact toolbar action: icon above a label, ≥ 88 dp tall. */
@Composable
fun ToolAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.Text1,
    highlighted: Boolean = false,
) {
    Row(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(CircleShape)
            .then(if (highlighted) Modifier.background(Palette.BrandGradient) else Modifier.glass(radius = 44.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = Type.Label, color = tint, maxLines = 1)
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .glass(radius = 24.dp)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        else Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.Caption, color = Palette.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Brand wave: 5 rounded bars whose heights follow the live level (§4.5, §7). */
@Composable
fun Waveform(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = Palette.Brand400,
    barWidth: Dp = 8.dp,
    gap: Dp = 6.dp,
    minHeight: Dp = 16.dp,
    maxHeight: Dp = 72.dp,
    animate: Boolean = true,
) {
    val reduce = LocalUiPrefs.current.reduceMotion
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "phase")
    val shape = floatArrayOf(0.45f, 0.85f, 1f, 0.7f, 0.4f)
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "lvl")
    Box(
        modifier
            .width(barWidth * 5 + gap * 4)
            .height(maxHeight)
            .drawBehind {
                val w = barWidth.toPx()
                val g = gap.toPx()
                val minH = minHeight.toPx()
                val maxH = size.height
                shape.forEachIndexed { i, s ->
                    val wobble = if (animate && !reduce) 0.75f + 0.25f * kotlin.math.sin((phase + i * 0.21f) * 6.28f) else 1f
                    val h = (minH + (maxH - minH) * lvl * s * wobble).coerceIn(minH, maxH)
                    drawRoundRect(color, Offset(i * (w + g), (maxH - h) / 2), Size(w, h), CornerRadius(w / 2))
                }
            },
    )
}

@Composable
fun SpeakerAvatar(
    speaker: Speaker?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    active: Boolean = false,
    showChannelBadge: Boolean = false,
) {
    val color = Palette.speaker(speaker?.colorIndex ?: 0)
    val reduce = LocalUiPrefs.current.reduceMotion
    val t = rememberInfiniteTransition(label = "halo")
    val halo by t.animateFloat(0.15f, 0.55f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "halo")
    Box(modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(color.copy(alpha = if (reduce) 0.3f else halo)))
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Palette.Bg2)
                .border(3.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val label = speaker?.name?.take(1) ?: speaker?.ordinal?.toString() ?: "?"
            Text(label, style = Type.TitleM.copy(fontSize = (size.value * 0.45f).sp), color = Palette.Text1)
        }
        if (showChannelBadge && speaker != null) {
            ChannelBadge(speaker.channel, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
fun ChannelBadge(channel: Channel, modifier: Modifier = Modifier) {
    val remote = channel == Channel.REMOTE
    Box(
        modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (remote) Palette.Brand700 else Palette.Bg2)
            .border(2.dp, Palette.Bg0, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            androidx.compose.ui.res.stringResource(if (remote) R.string.channel_remote_short else R.string.channel_local_short),
            style = Type.Caption.copy(fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
            color = Palette.Text1,
        )
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    icon: ImageVector? = null,
    accent: Color = Palette.Brand500,
) {
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .then(
                when {
                    selected -> Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), Palette.Brand700)))
                    else -> Modifier.glass(radius = height / 2)
                },
            )
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Text1, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.Label, color = if (enabled) Palette.Text1 else Palette.Text2.copy(alpha = 0.5f), maxLines = 1)
    }
}

/** Switch sized per §7 (96×56 dp). */
@Composable
fun BigSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "switch")
    Box(
        modifier
            .size(96.dp, 56.dp)
            .clip(CircleShape)
            .background(if (checked) Palette.Brand500 else Palette.Bg2)
            .border(1.dp, Palette.Stroke, CircleShape)
            .pressable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = (40 * x).dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun BrandLogo(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Image(painterResource(R.drawable.brand_logo), null, modifier.size(size).clip(CircleShape))
}

@Composable
fun BrandWordmark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandLogo(size = size)
        Spacer(Modifier.width(16.dp))
        Text("AI", style = Type.TitleL, color = Palette.Brand400)
        Text(" " + androidx.compose.ui.res.stringResource(R.string.brand_suffix), style = Type.TitleL)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.TitleM)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/**
 * In-meeting edge guard (§8): taps within 32 dp of the screen edge are swallowed so a hand
 * resting on the bezel cannot trigger controls.
 */
@Composable
fun EdgeGuard(modifier: Modifier = Modifier, width: Dp = 32.dp, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        val swallow = Modifier.pointerSwallow()
        Box(Modifier.align(Alignment.CenterStart).width(width).fillMaxHeight().then(swallow))
        Box(Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight().then(swallow))
        Box(Modifier.align(Alignment.TopCenter).height(width).fillMaxWidth().then(swallow))
        Box(Modifier.align(Alignment.BottomCenter).height(width).fillMaxWidth().then(swallow))
    }
}

private fun Modifier.pointerSwallow(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Stroke))
}

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = 24.dp, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(radius).padding(24.dp), content = content)
}

fun Modifier.maxWidthPanel(): Modifier = this.widthIn(max = 1200.dp)

val PillShape: Shape = CircleShape
