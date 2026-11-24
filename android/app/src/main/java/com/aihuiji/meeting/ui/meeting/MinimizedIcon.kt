package com.aihuiji.meeting.ui.meeting

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.ui.components.BrandLogo
import com.aihuiji.meeting.ui.theme.LocalUiPrefs
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val MinimizedIconSize = 176.dp

/**
 * Minimised form of the caption bar: the app icon with a radial waveform that pulses with the
 * live audio level. Tap to expand, drag to move.
 */
@Composable
fun MinimizedIcon(model: LiveUiModel?, chrome: BarChrome, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val recording = model != null
    val paused = model?.live?.isPaused == true
    val reduce = LocalUiPrefs.current.reduceMotion
    val animated = !paused && !reduce
    val level by animateFloatAsState(
        if (paused) 0f else model?.level() ?: 0.12f,
        tween(140),
        label = "iconLevel",
    )
    val t = rememberInfiniteTransition(label = "icon")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")
    val blink by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "blink")

    Box(modifier.fillMaxSize().then(chrome.move(onExpand)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val logoR = 50.dp.toPx()
            val inner = logoR + 9.dp.toPx()
            val ticks = 40
            val activePhase = if (animated) phase else 0f
            drawCircle(
                Brush.radialGradient(listOf(Palette.Brand500.copy(alpha = 0.10f + 0.30f * level), Color.Transparent), center = c, radius = inner + 26.dp.toPx()),
                radius = inner + 26.dp.toPx(),
                center = c,
            )
            if (animated) {
                for (k in 0..1) {
                    val p = (activePhase + k * 0.5f) % 1f
                    drawCircle(
                        Palette.Brand400.copy(alpha = (1f - p) * (0.12f + 0.55f * level)),
                        radius = logoR + 8.dp.toPx() + p * 26.dp.toPx(),
                        center = c,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            for (i in 0 until ticks) {
                val a = 2.0 * PI * i / ticks - PI / 2
                val wave = if (animated) 0.5f + 0.5f * sin(2.0 * PI * (activePhase * 2 + i / ticks.toFloat() * 3)).toFloat() else 0.2f
                val len = 5.dp.toPx() + 15.dp.toPx() * level * wave
                val color = lerp(Palette.Brand500, Color.White, (wave * level).coerceIn(0f, 1f)).copy(alpha = if (paused) 0.35f else 0.9f)
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(
                    color,
                    Offset(c.x + dx * inner, c.y + dy * inner),
                    Offset(c.x + dx * (inner + len), c.y + dy * (inner + len)),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        BrandLogo(size = 100.dp)

        if (recording) Box(
            Modifier.align(Alignment.TopStart).offset(112.dp, 36.dp).size(30.dp).clip(CircleShape)
                .background(Palette.Bg0).border(2.dp, Palette.Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (paused) {
                Icon(Icons.Outlined.Pause, null, tint = Palette.Warning, modifier = Modifier.size(20.dp))
            } else {
                Box(Modifier.size(14.dp).clip(CircleShape).alpha(if (animated) blink else 1f).background(Palette.Danger))
            }
        }
        if (model != null) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Palette.Bg0.copy(alpha = 0.92f))
                .border(1.dp, Palette.Stroke, RoundedCornerShape(17.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(formatClock(model.live.elapsedMs), style = Type.Label.copy(fontSize = 20.sp, fontFeatureSettings = "tnum"), color = Palette.Text1)
        }
    }
}
