package com.chesslens.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesslens.app.R
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.Tactics
import com.chesslens.vision.TrackingPhase
import kotlin.math.sin

@Composable
fun GlassChip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Lens.Glass)
            .border(1.dp, Lens.GlassBorder, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun AppMark(size: Dp = 26.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color(0xFF101513))
            .border(1.dp, Lens.GlassBorder, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun BrandChip(modifier: Modifier = Modifier) {
    GlassChip(modifier) {
        AppMark(22.dp)
        Text("ChessLens", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = true) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (pulse) a else 1f)))
}

@Composable
fun TrackingChip(phase: TrackingPhase?, fps: Float, modifier: Modifier = Modifier) {
    val (label, color) = when (phase) {
        TrackingPhase.LOCKED -> "Tracking" to Lens.Neon
        TrackingPhase.LOST -> "Re-acquiring" to Lens.Yellow
        TrackingPhase.SEARCHING -> "Finding board" to Lens.Yellow
        null -> "Camera off" to Lens.TextMuted
    }
    GlassChip(modifier) {
        Text("◎", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (phase == TrackingPhase.LOCKED && fps > 0) Text("${fps.toInt()} fps", color = Lens.TextSecondary, fontSize = 11.sp)
        StatusDot(color, pulse = phase != TrackingPhase.LOCKED)
    }
}

@Composable
fun EngineChip(depth: Int, evalText: String, source: String, searching: Boolean, modifier: Modifier = Modifier) {
    GlassChip(modifier) {
        Text(if (source == "Cloud") "Cloud" else "AI", color = Lens.Neon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("·", color = Lens.TextSecondary, fontSize = 12.sp)
        Text(if (depth > 0) "$depth moves deep" else if (searching) "thinking…" else "idle", color = Color.White, fontSize = 12.sp)
        if (evalText.isNotEmpty()) {
            Text("·", color = Lens.TextSecondary, fontSize = 12.sp)
            Text(evalText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "🔊 ||||||| Bishop takes D2, check" caption with an animated waveform. */
@Composable
fun VoicePill(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val t = rememberInfiniteTransition(label = "wave")
        val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(900)), label = "p")
        GlassChip {
            Text("🔊", fontSize = 12.sp)
            Canvas(Modifier.width(54.dp).height(16.dp)) {
                val bars = 13
                val w = size.width / (bars * 2f)
                for (i in 0 until bars) {
                    val h = size.height * (0.25f + 0.75f * kotlin.math.abs(sin(phase + i * 0.7f) * sin(phase * 0.5f + i)))
                    drawRoundRect(
                        Lens.Neon,
                        topLeft = Offset(i * w * 2, (size.height - h) / 2),
                        size = Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                    )
                }
            }
            Text(text.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Square-ish toggle used in the side panel: icon over a small label (AR, Voice, Undo, Game). */
@Composable
fun PanelButton(icon: ImageVector, label: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (active) Lens.Neon.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f)
    val fg = if (active) Lens.Neon else Color.White
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, if (active) Lens.Neon.copy(alpha = 0.5f) else Lens.GlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Lens.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Vertical evaluation bar for the 2D board (White at the bottom unless [flipped]). */
@Composable
fun EvalBar(whiteCp: Int, mate: Int?, flipped: Boolean, modifier: Modifier = Modifier) {
    val target = (Tactics.winPercent(whiteCp) / 100.0).toFloat()
    val anim by animateFloatAsState(target, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "bar")
    val label = mate?.let { if (it > 0) "M$it" else "M${-it}" } ?: String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(whiteCp) / 100.0)
    Box(modifier.width(22.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Lens.EvalBlack)) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * anim
            if (flipped) drawRect(Lens.EvalWhite, Offset.Zero, Size(size.width, h))
            else drawRect(Lens.EvalWhite, Offset(0f, size.height - h), Size(size.width, h))
            val y = if (flipped) h else size.height - h
            drawLine(Lens.Neon, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            drawLine(Lens.Neon.copy(alpha = 0.6f), Offset(0f, size.height / 2), Offset(size.width * 0.4f, size.height / 2), strokeWidth = 1.dp.toPx())
        }
        val whiteAhead = whiteCp >= 0
        val atBottom = whiteAhead != flipped
        Text(
            label,
            color = if (whiteAhead) Lens.EvalBlack else Lens.EvalWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(if (atBottom) Alignment.BottomCenter else Alignment.TopCenter).padding(vertical = 3.dp),
        )
    }
}

/** Evaluation graph over the game with the current ply marked; tap to jump. */
@Composable
fun EvalGraph(evals: List<Int?>, current: Int, qualities: Map<Int, MoveQuality>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Lens.Surface)
            .pointerInput(evals.size) {
                detectTapGestures { pos ->
                    val n = maxOf(evals.size, 2)
                    onSelect(((pos.x / size.width) * (n - 1) + 0.5f).toInt().coerceIn(0, maxOf(0, evals.size - 1)))
                }
            },
    ) {
        val n = maxOf(evals.size, 2)
        val stepX = size.width / (n - 1)
        fun y(cp: Int): Float {
            val w = (Tactics.winPercent(cp) / 100.0).toFloat()
            return size.height * (1 - w)
        }
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        val path = Path()
        val area = Path()
        var started = false
        var lastX = 0f
        evals.forEachIndexed { i, e ->
            val x = i * stepX
            val yy = y(e ?: 0)
            if (!started) {
                path.moveTo(x, yy); area.moveTo(x, size.height); area.lineTo(x, yy); started = true
            } else {
                path.lineTo(x, yy); area.lineTo(x, yy)
            }
            lastX = x
        }
        area.lineTo(lastX, size.height)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(Lens.EvalWhite.copy(alpha = 0.55f), Lens.EvalWhite.copy(alpha = 0.08f))))
        drawPath(path, Lens.Neon, style = Stroke(2.dp.toPx()))
        qualities.forEach { (ply, q) ->
            if (q == MoveQuality.BLUNDER || q == MoveQuality.MISTAKE || q == MoveQuality.BRILLIANT || q == MoveQuality.GREAT) {
                val i = ply + 1
                if (i < evals.size) drawCircle(Color(q.argb), 3.5.dp.toPx(), Offset(i * stepX, y(evals[i] ?: 0)))
            }
        }
        val cx = current * stepX
        drawLine(Lens.Neon.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
        drawCircle(Lens.Neon, 4.dp.toPx(), Offset(cx, y(evals.getOrNull(current) ?: 0)))
    }
}

@Composable
fun QualityIcon(q: MoveQuality, size: Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(q.argb)), contentAlignment = Alignment.Center) {
        val label = when (q) {
            MoveQuality.BEST -> "★"
            MoveQuality.EXCELLENT -> "!"
            MoveQuality.GOOD -> "✓"
            MoveQuality.BOOK -> "≡"
            else -> q.symbol
        }
        Text(label, color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BannerToast(text: String?, quality: MoveQuality?, error: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(text != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val accent = when {
            error -> Lens.Red
            quality != null -> Color(quality.argb)
            else -> Lens.Neon
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Lens.GlassStrong)
                .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (quality != null) QualityIcon(quality, 22.dp) else StatusDot(accent, pulse = false)
            Text(text.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
