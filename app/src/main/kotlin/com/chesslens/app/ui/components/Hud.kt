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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import com.chesslens.app.ui.theme.CalSans
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.Tactics
import com.chesslens.vision.TrackingPhase
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/** Solid dark pill for HUD text over the camera (legible on any footage, no glass). */
@Composable
fun HudPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(Color(0xEB131313))
            .border(1.dp, Color(0xFF2E2E2E), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            .border(1.dp, Lens.Rim, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = true) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (pulse) a else 1f)))
}

/** Centre status of the camera top bar: what the tracker is doing. */
@Composable
fun TrackingStatus(phase: TrackingPhase?, fps: Float, screenMode: Boolean) {
    val (label, color) = when (phase) {
        TrackingPhase.LOCKED -> "Tracking" to Lens.Green
        TrackingPhase.LOST -> "Re-acquiring" to Lens.Yellow
        TrackingPhase.SEARCHING -> (if (screenMode) "Finding screen" else "Finding board") to Lens.Yellow
        null -> "Camera off" to Lens.TextMuted
    }
    StatusDot(color, pulse = phase != TrackingPhase.LOCKED)
    Text(label, style = MaterialTheme.typography.titleMedium, color = Lens.Text)
    if (phase == TrackingPhase.LOCKED && fps > 0) Text("${fps.toInt()} fps", style = MaterialTheme.typography.bodySmall, color = Lens.TextMuted)
}

@Composable
fun EngineChip(depth: Int, evalText: String, source: String, searching: Boolean, modifier: Modifier = Modifier) {
    HudPill(modifier) {
        Text(if (source == "Cloud") "Cloud" else "AI", style = MaterialTheme.typography.labelMedium, color = Lens.Green)
        Text(
            if (depth > 0) "$depth moves deep" else if (searching) "thinking" else "idle",
            style = MaterialTheme.typography.labelMedium, color = Lens.Text,
        )
        if (evalText.isNotEmpty()) Text(evalText, style = MaterialTheme.typography.labelMedium, color = Lens.Text, fontWeight = FontWeight.Bold)
    }
}

/** Spoken hint caption with an animated waveform. */
@Composable
fun VoicePill(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
        modifier = modifier,
    ) {
        val t = rememberInfiniteTransition(label = "wave")
        val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(900)), label = "p")
        HudPill {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Lens.Green, modifier = Modifier.size(18.dp))
            Canvas(Modifier.width(54.dp).height(16.dp)) {
                val bars = 13
                val w = size.width / (bars * 2f)
                for (i in 0 until bars) {
                    val h = size.height * (0.25f + 0.75f * abs(sin(phase + i * 0.7f) * sin(phase * 0.5f + i)))
                    drawRoundRect(Lens.Green, topLeft = Offset(i * w * 2, (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
                }
            }
            Text(text.orEmpty(), style = MaterialTheme.typography.titleSmall, color = Lens.Text)
        }
    }
}

/** Bevelled tool button with a label under the icon (AR, Voice, Undo, Game). */
@Composable
fun PanelButton(icon: ImageVector, label: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BevelButton(onClick, modifier.heightIn(min = 56.dp), RoundedCornerShape(14.dp), active, androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp), label) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (active) Lens.Green else Lens.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) Lens.Green else Lens.TextDim)
        }
    }
}

/** Vertical evaluation bar for the 2D board (White at the bottom unless [flipped]). */
@Composable
fun EvalBar(whiteCp: Int, mate: Int?, flipped: Boolean, modifier: Modifier = Modifier) {
    val target = (Tactics.winPercent(whiteCp) / 100.0).toFloat()
    val anim by animateFloatAsState(target, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "bar")
    val label = mate?.let { if (it > 0) "M$it" else "M${-it}" } ?: String.format(Locale.US, "%.1f", abs(whiteCp) / 100.0)
    Box(
        modifier
            .width(24.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(Lens.EvalBlack)
            .border(1.dp, Lens.WellLine, RoundedCornerShape(8.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * anim
            if (flipped) drawRect(Lens.EvalWhite, Offset.Zero, Size(size.width, h))
            else drawRect(Lens.EvalWhite, Offset(0f, size.height - h), Size(size.width, h))
            val y = if (flipped) h else size.height - h
            drawLine(Lens.Green, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        }
        val whiteAhead = whiteCp >= 0
        val atBottom = whiteAhead != flipped
        Text(
            label,
            color = if (whiteAhead) Lens.EvalBlack else Lens.EvalWhite,
            fontSize = 10.sp,
            fontFamily = CalSans,
            modifier = Modifier.align(if (atBottom) Alignment.BottomCenter else Alignment.TopCenter).padding(vertical = 4.dp),
        )
    }
}

/** Evaluation graph over the game with the current ply marked; tap to jump. */
@Composable
fun EvalGraph(evals: List<Int?>, current: Int, qualities: Map<Int, MoveQuality>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .forgeWell(RoundedCornerShape(16.dp))
            .pointerInput(evals.size) {
                detectTapGestures { pos ->
                    val n = maxOf(evals.size, 2)
                    onSelect(((pos.x / size.width) * (n - 1) + 0.5f).toInt().coerceIn(0, maxOf(0, evals.size - 1)))
                }
            },
    ) {
        val n = maxOf(evals.size, 2)
        val stepX = size.width / (n - 1)
        fun y(cp: Int): Float = size.height * (1 - (Tactics.winPercent(cp) / 100.0).toFloat())
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        val path = Path()
        val area = Path()
        var lastX = 0f
        evals.forEachIndexed { i, e ->
            val x = i * stepX
            val yy = y(e ?: 0)
            if (i == 0) {
                path.moveTo(x, yy); area.moveTo(x, size.height); area.lineTo(x, yy)
            } else {
                path.lineTo(x, yy); area.lineTo(x, yy)
            }
            lastX = x
        }
        area.lineTo(lastX, size.height)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(Lens.EvalWhite.copy(alpha = 0.5f), Lens.EvalWhite.copy(alpha = 0.06f))))
        drawPath(path, Lens.Green, style = Stroke(2.dp.toPx()))
        qualities.forEach { (ply, q) ->
            if (q == MoveQuality.BLUNDER || q == MoveQuality.MISTAKE || q == MoveQuality.BRILLIANT || q == MoveQuality.GREAT) {
                val i = ply + 1
                if (i < evals.size) drawCircle(Color(q.argb), 4.dp.toPx(), Offset(i * stepX, y(evals[i] ?: 0)))
            }
        }
        val cx = current * stepX
        drawLine(Lens.Green.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
        drawCircle(Lens.Green, 4.5.dp.toPx(), Offset(cx, y(evals.getOrNull(current) ?: 0)))
    }
}

/** Drawn icon for the qualities that are not chess annotation marks. */
fun qualityIcon(q: MoveQuality): ImageVector? = when (q) {
    MoveQuality.BEST -> Icons.Rounded.Star
    MoveQuality.EXCELLENT -> Icons.Rounded.ThumbUp
    MoveQuality.GOOD -> Icons.Rounded.Check
    MoveQuality.BOOK -> Icons.AutoMirrored.Rounded.MenuBook
    else -> null
}

/** Move-quality badge: annotation marks (!!, ?!, ??) as type, the rest as drawn icons. */
@Composable
fun QualityIcon(q: MoveQuality, size: Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(q.argb)), contentAlignment = Alignment.Center) {
        val icon = qualityIcon(q)
        if (icon != null) Icon(icon, contentDescription = q.label, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        else Text(q.symbol, color = Color.White, fontSize = (size.value * 0.5f).sp, fontFamily = CalSans)
    }
}

/** Transient announcement over the camera (move quality, recognition problems). */
@Composable
fun BannerToast(text: String?, quality: MoveQuality?, error: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        text != null, modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
    ) {
        val accent = when {
            error -> Lens.Red
            quality != null -> Color(quality.argb)
            else -> Lens.Green
        }
        Row(
            Modifier
                .forgeSlab(RoundedCornerShape(18.dp), elevation = 10.dp)
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (quality != null) QualityIcon(quality, 24.dp) else StatusDot(accent, pulse = false)
            Text(text.orEmpty(), style = MaterialTheme.typography.titleSmall, color = Lens.Text)
        }
    }
}
