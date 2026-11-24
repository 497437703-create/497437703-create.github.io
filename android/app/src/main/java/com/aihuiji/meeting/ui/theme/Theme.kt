package com.aihuiji.meeting.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** Design tokens from UI spec §4.1. */
object Palette {
    val Brand400 = Color(0xFFC27CF3)
    val Brand500 = Color(0xFF8F4BE0)
    val Brand700 = Color(0xFF6B3AD4)
    val Bg0 = Color(0xFF0E0A18)
    val Bg1 = Color(0xFF171128)
    val Bg2 = Color(0xFF211938)
    val Text1 = Color(0xFFF5F2FC)
    val Text2 = Color(0xFFB9B0D0)
    val TextTrans = Color(0xFFD8C6FF)
    val Success = Color(0xFF4ADE9A)
    val Warning = Color(0xFFFFC24D)
    val Danger = Color(0xFFFF5C7A)
    val LightBg = Color(0xFFF3EEFC)
    val Stroke = Color(0x1FFFFFFF)
    val Highlight = Color(0x14FFFFFF)

    val BrandGradient = Brush.linearGradient(listOf(Brand400, Brand500, Brand700))
    val GlassTopHighlight = Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color(0x00FFFFFF)))

    /** Speaker ring colours (§4.1); assignment order keeps neighbours distinct. */
    val Speakers = listOf(
        Color(0xFF8F4BE0), Color(0xFF3FB6A8), Color(0xFFE8A33D), Color(0xFFE2618F),
        Color(0xFF5B8DEF), Color(0xFF9AD14B), Color(0xFFE07B4C), Color(0xFFB07CF0),
    )

    fun speaker(index: Int) = Speakers[index.mod(Speakers.size)]
}

/** Type scale from UI spec §4.3 (dp == sp at the G1's default font scale). */
object Type {
    private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val Display = TextStyle(fontSize = 56.sp, lineHeight = 68.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text1, lineHeightStyle = tight)
    val TitleL = TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text1)
    val TitleM = TextStyle(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, color = Palette.Text1)
    val Body = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, color = Palette.Text1)
    val Caption = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal, color = Palette.Text2)
    val Label = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, color = Palette.Text1)
}

@Immutable
data class UiPrefs(val reduceMotion: Boolean = false)

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

@Composable
fun AiHuijiTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Brand500,
        onPrimary = Color.White,
        secondary = Palette.Brand400,
        background = Palette.Bg0,
        onBackground = Palette.Text1,
        surface = Palette.Bg1,
        onSurface = Palette.Text1,
        surfaceVariant = Palette.Bg2,
        onSurfaceVariant = Palette.Text2,
        error = Palette.Danger,
        outline = Palette.Stroke,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            displayLarge = Type.Display,
            titleLarge = Type.TitleL,
            titleMedium = Type.TitleM,
            bodyLarge = Type.Body,
            bodyMedium = Type.Body,
            labelLarge = Type.Label,
            bodySmall = Type.Caption,
        ),
        content = content,
    )
}
