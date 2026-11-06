package com.chesslens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette lifted from the AR look: mint neon on dark glass. */
object Lens {
    val Neon = Color(0xFF5BF08D)
    val NeonSoft = Color(0xFF7FF0A8)
    val NeonDeep = Color(0xFF1FBF5B)
    val PillGreen = Color(0xFF79E99A)
    val PillText = Color(0xFF07250F)
    val Yellow = Color(0xFFF7D64A)
    val YellowText = Color(0xFF3A2C00)
    val Red = Color(0xFFFF4D57)
    val Blue = Color(0xFF4F95FF)
    val Purple = Color(0xFFB07CFF)
    val Glass = Color(0xB3141A17)
    val GlassStrong = Color(0xE6101412)
    val GlassBorder = Color(0x33FFFFFF)
    val Background = Color(0xFF0B0F0D)
    val Surface = Color(0xFF151B18)
    val SurfaceHigh = Color(0xFF1D2521)
    val TextPrimary = Color(0xFFF2F6F3)
    val TextSecondary = Color(0xFFA4B0AA)
    val TextMuted = Color(0xFF6E7A74)
    val BoardLight = Color(0xFFEFDDBE)
    val BoardDark = Color(0xFFB98B62)
    val EvalWhite = Color(0xFFF4F4F0)
    val EvalBlack = Color(0xFF26292A)

    /** Colours for engine lines 1..3. */
    val LineColors = listOf(Neon, Blue, Purple)
}

private val scheme = darkColorScheme(
    primary = Lens.Neon,
    onPrimary = Lens.PillText,
    secondary = Lens.Blue,
    tertiary = Lens.Purple,
    background = Lens.Background,
    onBackground = Lens.TextPrimary,
    surface = Lens.Surface,
    onSurface = Lens.TextPrimary,
    surfaceVariant = Lens.SurfaceHigh,
    onSurfaceVariant = Lens.TextSecondary,
    error = Lens.Red,
)

@Composable
fun ChessLensTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

val MonoDigits = TextStyle(fontFamily = FontFamily.Monospace)
