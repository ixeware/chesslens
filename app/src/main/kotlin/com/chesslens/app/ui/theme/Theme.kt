package com.chesslens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chesslens.app.R

/**
 * Forge-style dark material with a green signal colour. Surfaces are near-black slabs lit from
 * the top; controls are bevelled; the one saturated colour marks actions, selection and the AR
 * layer. See DESIGN.md.
 */
object Lens {
    // Ground and slabs.
    val Ground = Color(0xFF0B0B0B)
    val Slab = Color(0xFF101010)
    val SlabLit = Color(0xFF1B1B1B)
    val Well = Color(0xFF0A0A0A)
    val WellLine = Color(0xFF1D1D1D)
    val Line = Color(0xFF242424)
    val Rim = Color(0x2EFFFFFF)
    val ControlTop = Color(0xFF2A2A2A)
    val ControlBottom = Color(0xFF161414)
    val ControlBorder = Color(0x38B5B5B5)

    // Signal green (actions, selection, AR).
    val Green = Color(0xFF22E07A)
    val GreenGlow = Color(0xFF14C866)
    val GreenDeep = Color(0xFF003D1C)
    val GreenInk = Color(0xFF04230F)
    /** The AR neon from the reference video (on top of camera footage). */
    val Neon = Color(0xFF5BF08D)
    val NeonSoft = Color(0xFF9DFFC0)
    val PillGreen = Color(0xFF79E99A)
    val PillText = Color(0xFF07250F)

    // Secondary signals.
    val Yellow = Color(0xFFF7D64A)
    /** "Check this": squares or values the app isn't sure about. */
    val Amber = Color(0xFFFFB020)
    val YellowText = Color(0xFF3A2C00)
    val Red = Color(0xFFFF5A5F)
    val Blue = Color(0xFF4F95FF)
    val Purple = Color(0xFFB07CFF)

    // Text.
    val Text = Color(0xFFEDEDED)
    val TextDim = Color(0xB3EDEDED)
    val TextMuted = Color(0x80FFFFFF)
    val TextFaint = Color(0x59FFFFFF)

    // Board + eval.
    val BoardLight = Color(0xFFEFDDBE)
    val BoardDark = Color(0xFFB98B62)
    val EvalWhite = Color(0xFFF4F4F0)
    val EvalBlack = Color(0xFF26292A)

    /** Engine lines 1..3. */
    val LineColors = listOf(Neon, Blue, Purple)

    // Legacy aliases used by the AR layer and overlays.
    val Background = Ground
    val Surface = Slab
    val TextPrimary = Text
    val TextSecondary = TextDim
    val GlassStrong = Color(0xF0101010)
}

/** Cal Sans: display and labels (the forge "label" voice). */
val CalSans = FontFamily(Font(R.font.cal_sans, FontWeight.SemiBold), Font(R.font.cal_sans, FontWeight.Normal))

/** Inter Tight: UI and body text, variable weight. */
@OptIn(ExperimentalTextApi::class)
val InterTight = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.inter_tight, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val scheme = darkColorScheme(
    primary = Lens.Green,
    onPrimary = Lens.GreenInk,
    primaryContainer = Lens.GreenDeep,
    onPrimaryContainer = Lens.NeonSoft,
    secondary = Lens.Blue,
    tertiary = Lens.Purple,
    background = Lens.Ground,
    onBackground = Lens.Text,
    surface = Lens.Slab,
    onSurface = Lens.Text,
    surfaceVariant = Lens.SlabLit,
    onSurfaceVariant = Lens.TextDim,
    surfaceContainerLow = Lens.Slab,
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Lens.SlabLit,
    outline = Lens.ControlBorder,
    outlineVariant = Lens.Line,
    error = Lens.Red,
)

private fun ui(size: Int, weight: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = InterTight, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private fun display(size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = CalSans, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/** Material type roles mapped to the two forge voices (ratio ≈1.2, product scale). */
val LensType = Typography(
    displayLarge = display(52, 56, -1.2),
    displayMedium = display(42, 46, -0.9),
    displaySmall = display(34, 38, -0.6),
    headlineLarge = display(30, 34, -0.4),
    headlineMedium = display(26, 30, -0.3),
    headlineSmall = display(22, 26, -0.2),
    titleLarge = display(20, 24),
    titleMedium = ui(16, 600, 22),
    titleSmall = ui(14, 600, 20),
    bodyLarge = ui(16, 400, 24),
    bodyMedium = ui(14, 400, 20),
    bodySmall = ui(12, 400, 16),
    labelLarge = display(16, 20),
    labelMedium = ui(13, 600, 16),
    labelSmall = ui(11, 600, 14, 0.2),
)

@Composable
fun ChessLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = LensType, content = content)
}
