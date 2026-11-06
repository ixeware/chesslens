package com.chesslens.app.ui.ar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import com.chesslens.app.game.UiState
import com.chesslens.app.game.VisionUi
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Move
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.Piece
import com.chesslens.vision.TrackingPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class Label(
    val square: Int,
    val anchor: Offset,
    val title: String,
    val sub: String?,
    val bg: Color,
    val fg: Color,
    val scale: Float,
    val alpha: Float,
    val sp: Float,
)

/** Transient effect triggers coming from game events. */
data class OverlayFx(
    val lockNanos: Long = 0L,
    val waveNanos: Long = 0L,
    val waveOrigin: Int = -1,
    val badge: BadgeFx? = null,
)

data class BadgeFx(val square: Int, val quality: MoveQuality, val nanos: Long)

/** Time source for effects (overridable for deterministic screenshots). */
val LocalFxClock = staticCompositionLocalOf<() -> Long> { System::nanoTime }

@Composable
fun rememberFrameNanos(): State<Long> {
    val clock = LocalFxClock.current
    val s = remember { mutableLongStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) withFrameNanos { s.longValue = clock() }
    }
    return s
}

/** Exponentially smooths the tracked quad (camera rate) to display rate. */
private class QuadSmoother {
    var quad: List<Offset>? = null
    var lastNs = 0L
    var handAlpha = 1f

    fun update(target: List<Offset>?, hand: Boolean, now: Long): List<Offset>? {
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        val k = 1f - exp(-dt * 16f)
        handAlpha += ((if (hand) 0.22f else 1f) - handAlpha) * (1f - exp(-dt * 8f))
        if (target == null) {
            quad = null
            return null
        }
        val cur = quad
        if (cur == null) {
            quad = target
            return target
        }
        val diag = hypot(target[2].x - target[0].x, target[2].y - target[0].y)
        val jump = (0..3).maxOf { (cur[it] - target[it]).getDistance() }
        quad = if (jump > diag * 0.25f) target else (0..3).map { cur[it] + (target[it] - cur[it]) * k }
        return quad
    }
}

/**
 * The AR layer drawn over the camera preview: neon board brackets, glowing move ribbons,
 * floating move pills, scan rings, check alerts and — in analysis mode — multi-line arrows,
 * threats, hanging pieces, a control heat-map and a floating evaluation bar.
 */
@Composable
fun ArOverlay(vision: VisionUi?, state: UiState, fx: OverlayFx, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val painter = remember(density) { Fx(density) }
    val smoother = remember { QuadSmoother() }
    val frame = rememberFrameNanos()
    val model = remember(state.position, state.analysis, state.threat, state.settings) { OverlayModel.from(state) }
    val clock = LocalFxClock.current
    val bestSince = remember(model.best?.move, state.position.fen) { clock() }
    val lastSince = remember(model.lastMove, state.position.fen) { clock() }
    val evalAnim by animateFloatAsState(model.whiteWin, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow), label = "eval")
    val evalJump = remember { EvalJump() }

    Canvas(modifier.fillMaxSize()) {
        val now = frame.value
        val hand = vision?.handOverBoard == true
        val quad = smoother.update(if (vision?.phase == TrackingPhase.SEARCHING) null else vision?.viewQuad, hand, now)
        if (vision == null || quad == null) {
            drawSearchGuide(painter, now)
            return@Canvas
        }
        if (!state.settings.arEnabled) {
            drawBrackets(painter, ArProjector(quad, vision.orientation), now, fx, alpha = 0.6f)
            return@Canvas
        }
        val pj = ArProjector(quad, vision.orientation)
        val hintAlpha = smoother.handAlpha
        val lost = vision.phase == TrackingPhase.LOST

        model.control?.let { drawHeat(painter, pj, it, hintAlpha) }
        drawBrackets(painter, pj, now, fx, alpha = if (lost) 0.4f else 1f)
        drawLockSweep(painter, pj, now, fx.lockNanos)
        drawRingWave(painter, pj, model.squares, now, fx)
        if (lost) return@Canvas

        // Opponent's (last) move in yellow.
        if (model.lastMove != Move.NONE) {
            val a = hintAlpha * ease(((now - lastSince) / 3e8f).coerceIn(0f, 1f))
            painter.fill(this, pj.squarePath(Move.from(model.lastMove), 0.04f), Lens.Yellow, 0.16f * a)
            painter.fill(this, pj.squarePath(Move.to(model.lastMove), 0.04f), Lens.Yellow, 0.32f * a, glow = painter.dp(10f))
            painter.stroke(this, pj.squarePath(Move.to(model.lastMove), 0.04f), Lens.Yellow, painter.dp(1.5f), 0.8f * a)
        }

        model.hanging.forEach { sq -> drawHanging(painter, pj, sq, now, hintAlpha) }
        model.threat?.let { drawArrowLine(painter, pj, it, now, bestSince, hintAlpha * 0.9f, width = 0.13f, dashed = true) }
        model.alts.forEach { drawArrowLine(painter, pj, it, now, bestSince, hintAlpha * 0.85f, width = 0.15f) }
        if (model.showBest) {
            drawPvPreview(painter, pj, model, hintAlpha)
            model.best?.let { drawBestMove(painter, pj, it, now, bestSince, hintAlpha) }
        }
        if (model.checkSquare >= 0) drawCheck(painter, pj, model.checkSquare, now, hintAlpha)

        // Labels on top: laid out by priority so they never overlap, then drawn back-to-front.
        val labels = ArrayList<Label>()
        if (model.checkSquare >= 0) {
            val pulse = 1f + 0.06f * sin(now / 1e9f * 8f)
            labels += Label(model.checkSquare, labelAnchor(pj, model.checkSquare, now, 0.95f), "CHECK", null, Lens.Red, Color.White, pulse, 1f, 13f)
        }
        if (model.showBest) model.best?.let { b ->
            val pop = popScale((now - bestSince) / 1e9f - 0.35f)
            labels += Label(Move.to(b.move), labelAnchor(pj, Move.to(b.move), now), b.san, b.evalText, Lens.PillGreen, Lens.PillText, pop, hintAlpha, 15f)
        }
        if (model.lastMove != Move.NONE && model.lastSan != null && !(model.showBest && model.best != null && Move.to(model.best.move) == Move.to(model.lastMove))) {
            val sq = Move.to(model.lastMove)
            labels += Label(sq, labelAnchor(pj, sq, now), model.lastSan, null, Lens.Yellow, Lens.YellowText, popScale((now - lastSince) / 1e9f), hintAlpha * 0.95f, 15f)
        }
        model.alts.forEach { lv ->
            val sq = Move.to(lv.move)
            labels += Label(sq, labelAnchor(pj, sq, now, 0.55f), lv.san, lv.evalText, lv.color, Color.White, popScale((now - bestSince) / 1e9f - 0.25f) * 0.85f, hintAlpha, 12f)
        }
        model.threat?.let { t ->
            labels += Label(Move.to(t.move), labelAnchor(pj, Move.to(t.move), now, 0.5f), "threat", t.san, Lens.Red, Color.White, popScale((now - bestSince) / 1e9f - 0.4f) * 0.85f, hintAlpha, 11f)
        }
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        val positioned = labels.map { l ->
            val (w0, h0) = painter.pillSize(l.title, l.sub, l.sp)
            val w = w0 * l.scale.coerceAtLeast(0.6f)
            val h = h0 * l.scale.coerceAtLeast(0.6f)
            var a = l.anchor
            repeat(8) {
                val r = androidx.compose.ui.geometry.Rect(a.x - w / 2, a.y - h, a.x + w / 2, a.y)
                val hit = placed.firstOrNull { it.overlaps(r) } ?: return@repeat
                a = Offset(a.x, hit.top - painter.dp(3f))
            }
            placed += androidx.compose.ui.geometry.Rect(a.x - w / 2, a.y - h, a.x + w / 2, a.y)
            l to a
        }
        for ((l, a) in positioned.reversed()) {
            if (a != l.anchor) {
                // Leader line back to the square when the label had to move.
                painter.line(this, a, pj.center(l.square), l.bg, painter.dp(1.2f), 0.7f * l.alpha)
            }
            painter.pill(this, a, l.title, l.sub, l.bg, l.fg, l.scale, l.alpha, titleSp = l.sp)
        }
        fx.badge?.let { drawBadge(painter, pj, it, now) }
        vision.changedSquares.forEach { sq ->
            val a = 0.5f + 0.5f * sin(now / 1e9f * 6f)
            painter.stroke(this, pj.squarePath(sq, 0.06f), Lens.Red, painter.dp(2f), a, glow = painter.dp(6f))
        }
        if (model.analysisMode) {
            drawFlyingEvalBar(painter, pj, evalAnim, model, now, evalJump)
        }
    }
}

// ------------------------------------------------------------------------------------ pieces

private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/** Overshooting pop-in (0 → 1.08 → 1). */
private fun popScale(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 0.45f) return 1f
    val x = t / 0.45f
    val c1 = 1.70158f
    val c3 = c1 + 1
    return 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1)
}

/** Screen point above a square where its label floats (gentle bob). */
private fun labelAnchor(pj: ArProjector, sq: Int, now: Long, lift: Float = 0.75f): Offset {
    val c = pj.center(sq)
    val size = pj.squareSize(sq)
    val bob = sin(now / 1e9f * 2.4f + sq) * size * 0.04f
    return Offset(c.x, c.y - size * lift + bob)
}

private fun DrawScope.drawSearchGuide(p: Fx, now: Long) {
    val s = min(size.width, size.height) * 0.62f
    val cx = size.width / 2
    val cy = size.height / 2
    val pulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(now / 1e9f * 3f))
    val l = s * 0.16f
    val x0 = cx - s / 2
    val y0 = cy - s / 2
    val x1 = cx + s / 2
    val y1 = cy + s / 2
    val w = p.dp(3.5f)
    val col = Color.White
    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        val path = Path().apply { moveTo(x + dx * l, y); lineTo(x, y); lineTo(x, y + dy * l) }
        p.stroke(this, path, Lens.Neon, w, pulse * 0.6f, glow = p.dp(8f))
        p.stroke(this, path, col, w * 0.6f, pulse)
    }
    bracket(x0, y0, 1f, 1f); bracket(x1, y0, -1f, 1f); bracket(x1, y1, -1f, -1f); bracket(x0, y1, 1f, -1f)
    // Sweeping scan line.
    val t = (now / 1e9f * 0.6f) % 1f
    val y = y0 + (y1 - y0) * t
    p.line(this, Offset(x0 + w * 2, y), Offset(x1 - w * 2, y), Lens.Neon, p.dp(2f), 0.8f, glow = p.dp(10f))
}

private fun DrawScope.drawBrackets(p: Fx, pj: ArProjector, now: Long, fx: OverlayFx, alpha: Float) {
    val sinceLock = (now - fx.lockNanos) / 1e9f
    val snap = if (fx.lockNanos == 0L) 1f else ease((sinceLock / 0.45f).coerceIn(0f, 1f))
    val flash = if (fx.lockNanos != 0L && sinceLock < 0.8f) (1f - sinceLock / 0.8f) else 0f
    val l = 1.0f
    val corners = listOf(
        Triple(Offset(0f, 0f), Offset(l, 0f), Offset(0f, l)),
        Triple(Offset(8f, 0f), Offset(8f - l, 0f), Offset(8f, l)),
        Triple(Offset(8f, 8f), Offset(8f - l, 8f), Offset(8f, 8f - l)),
        Triple(Offset(0f, 8f), Offset(l, 8f), Offset(0f, 8f - l)),
    )
    val center = Offset(size.width / 2, size.height / 2)
    val guide = min(size.width, size.height) * 0.62f
    fun guidePt(b: Offset) = Offset(center.x + (b.x / 8f - 0.5f) * guide, center.y + (b.y / 8f - 0.5f) * guide)
    val w = p.dp(4f)
    for ((c, a, b) in corners) {
        fun pt(q: Offset): Offset {
            val target = pj.board(q.x, q.y)
            return if (snap >= 1f) target else guidePt(q) + (target - guidePt(q)) * snap
        }
        val path = Path().apply {
            val pa = pt(a); val pc = pt(c); val pb = pt(b)
            moveTo(pa.x, pa.y); lineTo(pc.x, pc.y); lineTo(pb.x, pb.y)
        }
        p.stroke(this, path, Lens.Neon, w * 1.6f, alpha * (0.5f + flash * 0.5f), glow = p.dp(10f))
        p.stroke(this, path, Lens.Neon, w, alpha)
        p.stroke(this, path, Color.White, w * 0.35f, alpha * 0.7f)
    }
}

/** Grid lines sweeping across the board right after it locks. */
private fun DrawScope.drawLockSweep(p: Fx, pj: ArProjector, now: Long, lockNs: Long) {
    if (lockNs == 0L) return
    val t = (now - lockNs) / 1e9f
    if (t > 1.4f) return
    val front = t / 1.1f * 10f - 1f
    for (k in 0..8) {
        val d = k - front
        val a = (exp(-d * d / 1.5f) * 0.9f + if (d < 0) 0.12f * (1f - t / 1.4f) else 0f).coerceIn(0f, 1f)
        if (a < 0.02f) continue
        p.line(this, pj.board(k.toFloat(), 0f), pj.board(k.toFloat(), 8f), Lens.Neon, p.dp(1.5f), a, glow = p.dp(5f))
        p.line(this, pj.board(0f, k.toFloat()), pj.board(8f, k.toFloat()), Lens.Neon, p.dp(1.5f), a, glow = p.dp(5f))
    }
}

/** "Reading the game" rings that ripple over every piece (on lock and after detected moves). */
private fun DrawScope.drawRingWave(p: Fx, pj: ArProjector, squares: IntArray, now: Long, fx: OverlayFx) {
    val sources = listOfNotNull(
        if (fx.lockNanos != 0L) fx.lockNanos to -1 else null,
        if (fx.waveNanos != 0L) fx.waveNanos to fx.waveOrigin else null,
    )
    for ((start, origin) in sources) {
        val t = (now - start) / 1e9f
        if (t < 0 || t > 2.2f) continue
        for (sq in 0 until 64) {
            if (squares[sq] == 0) continue
            val f = sq and 7
            val r = sq shr 3
            val dist = if (origin < 0) (r + f * 0.35f) / 10f else hypot((f - (origin and 7)).toFloat(), (r - (origin shr 3)).toFloat()) / 9f
            val local = (t - 0.25f - dist * 0.9f) / 0.7f
            if (local < 0 || local > 1) continue
            val a = sin(local * PI.toFloat())
            val radius = 0.3f + 0.12f * local
            val ring = pj.circle(f + 0.5f, r + 0.5f, radius)
            p.stroke(this, ring, Lens.Neon, p.dp(2.2f), a, glow = p.dp(6f))
            p.fill(this, ring, Lens.Neon, a * 0.12f)
        }
    }
}

private fun DrawScope.drawBestMove(p: Fx, pj: ArProjector, lv: LineView, now: Long, since: Long, alpha: Float) {
    val t = (now - since) / 1e9f
    val grow = ease((t / 0.55f).coerceIn(0f, 1f))
    val from = Move.from(lv.move)
    val to = Move.to(lv.move)
    val pulse = 0.5f + 0.5f * sin(t * 4f)
    // Source & destination squares.
    p.fill(this, pj.squarePath(from, 0.05f), Lens.Neon, 0.16f * alpha)
    if (grow > 0.6f) {
        val ga = alpha * ((grow - 0.6f) / 0.4f)
        val dest = pj.squarePath(to, 0.05f)
        p.fill(this, dest, Lens.Neon, (0.28f + 0.14f * pulse) * ga, glow = p.dp(14f))
        p.fill(this, dest, Lens.Neon, 0.22f * ga)
        p.stroke(this, dest, Lens.NeonSoft, p.dp(2f), 0.9f * ga, glow = p.dp(3f))
    }
    // Glowing ribbon.
    val pts = Ribbon.movePath(from, to, lv.isKnight)
    val outline = Ribbon.arrow(pts, width = 0.3f, headWidth = 0.62f, headLength = 0.42f, progress = grow, startInset = 0.18f, endInset = 0.12f)
    val path = pj.polygon(outline)
    p.fill(this, path, Lens.Neon, 0.55f * alpha, glow = p.dp(12f))
    p.fill(this, path, Lens.Neon, 0.5f * alpha)
    p.stroke(this, path, Lens.NeonSoft, p.dp(1.6f), 0.95f * alpha)
    // Shimmer travelling along the ribbon.
    if (grow >= 1f) {
        val len = Ribbon.length(pts)
        val f = (t * 0.75f) % 1f
        val pos = pj.chess(Ribbon.pointAt(pts, 0.2f + (len - 0.4f) * f))
        p.circle(this, pos, p.dp(4f), Color.White, alpha * sin(f * PI.toFloat()), glow = p.dp(10f))
    }
}

private fun DrawScope.drawArrowLine(p: Fx, pj: ArProjector, lv: LineView, now: Long, since: Long, alpha: Float, width: Float, dashed: Boolean = false) {
    val t = (now - since) / 1e9f - 0.2f
    val grow = ease((t / 0.55f).coerceIn(0f, 1f))
    if (grow <= 0f) return
    val pts = Ribbon.movePath(Move.from(lv.move), Move.to(lv.move), lv.isKnight)
    if (dashed) {
        val len = Ribbon.length(pts)
        val dash = 0.28f
        var d = 0.2f
        val offset = (t * 0.6f) % (dash * 2)
        while (d < len * grow - 0.4f) {
            val seg = Ribbon.slice(pts, d + offset, min(d + offset + dash, len * grow - 0.35f), 0.08f)
            if (seg.size >= 2) {
                val ribbon = Ribbon.arrow(seg, width, width, 0.01f, 1f, 0f, 0f)
                p.fill(this, pj.polygon(ribbon), lv.color, 0.85f * alpha, glow = p.dp(6f))
            }
            d += dash * 2
        }
        val head = Ribbon.arrow(pts, width, 0.42f, 0.32f, grow, max(0.2f, Ribbon.length(pts) * grow - 0.45f), 0.1f)
        p.fill(this, pj.polygon(head), lv.color, 0.9f * alpha, glow = p.dp(6f))
        return
    }
    val outline = Ribbon.arrow(pts, width, width * 2.6f, 0.34f, grow, 0.22f, 0.12f)
    val path = pj.polygon(outline)
    p.fill(this, path, lv.color, 0.45f * alpha, glow = p.dp(9f))
    p.fill(this, path, lv.color, 0.6f * alpha)
    p.stroke(this, path, Color.White, p.dp(1f), 0.5f * alpha)
}

private fun DrawScope.drawPvPreview(p: Fx, pj: ArProjector, model: OverlayModel, alpha: Float) {
    model.pv.forEachIndexed { i, m ->
        val from = pj.center(Move.from(m))
        val to = pj.center(Move.to(m))
        val col = if ((i % 2) == 0) Color(0xFFFF8A80) else Color(0xFFB9F6CA)
        p.line(this, from, to, col, p.dp(1.6f), 0.55f * alpha)
        val r = pj.squareSize(Move.to(m)) * 0.16f
        p.circle(this, to, r, Color(0xCC0E1411), alpha)
        p.circle(this, to, r, col, alpha, strokeWidth = p.dp(1.4f))
        p.text(this, "${i + 2}", to.x, to.y + r * 0.42f, (r / p.dp(1f)) * 1.1f, col, alpha = alpha, center = true)
    }
}

private fun DrawScope.drawCheck(p: Fx, pj: ArProjector, sq: Int, now: Long, alpha: Float) {
    val t = now / 1e9f
    val pulse = 0.5f + 0.5f * sin(t * 6f)
    p.fill(this, pj.squarePath(sq, 0.04f), Lens.Red, (0.25f + 0.2f * pulse) * alpha, glow = p.dp(16f))
    val ring = pj.circle((sq and 7) + 0.5f, (sq shr 3) + 0.5f, 0.42f + 0.05f * pulse)
    p.stroke(this, ring, Lens.Red, p.dp(2.5f), alpha, glow = p.dp(8f))
}

private fun DrawScope.drawHanging(p: Fx, pj: ArProjector, sq: Int, now: Long, alpha: Float) {
    val pulse = 0.5f + 0.5f * sin(now / 1e9f * 5f + sq)
    val ring = pj.circle((sq and 7) + 0.5f, (sq shr 3) + 0.5f, 0.38f)
    p.stroke(this, ring, Lens.Red, p.dp(2f), (0.45f + 0.45f * pulse) * alpha, glow = p.dp(6f))
    val c = pj.center(sq)
    val s = pj.squareSize(sq)
    val dot = Offset(c.x + s * 0.32f, c.y - s * 0.45f)
    p.circle(this, dot, p.dp(7f), Lens.Red, alpha, glow = p.dp(5f))
    p.text(this, "!", dot.x, dot.y + p.dp(3.8f), 11f, Color.White, alpha = alpha, center = true)
}

private fun DrawScope.drawHeat(p: Fx, pj: ArProjector, control: IntArray, alpha: Float) {
    for (sq in 0 until 64) {
        val v = control[sq]
        if (v == 0) continue
        val col = if (v > 0) Lens.Neon else Lens.Red
        val a = (min(abs(v), 3) / 3f) * 0.3f * alpha
        p.fill(this, pj.squarePath(sq, 0.02f), col, a)
    }
}

private fun DrawScope.drawBadge(p: Fx, pj: ArProjector, b: BadgeFx, now: Long) {
    val t = (now - b.nanos) / 1e9f
    if (t < 0 || t > 3.2f) return
    val col = Color(b.quality.argb)
    val c = pj.center(b.square)
    val s = pj.squareSize(b.square)
    val origin = Offset(c.x + s * 0.32f, c.y - s * 0.62f - t * p.dp(4f))
    p.burst(this, origin, t, col, count = 16, seed = b.square)
    val scale = popScale(t)
    val fade = if (t > 2.6f) 1f - (t - 2.6f) / 0.6f else 1f
    val r = p.dp(14f) * scale
    p.circle(this, origin, r * 1.25f, col, 0.45f * fade, glow = p.dp(12f))
    p.circle(this, origin, r, col, fade)
    p.circle(this, origin, r, Color.White, 0.9f * fade, strokeWidth = p.dp(1.5f))
    val label = when (b.quality) {
        MoveQuality.BEST -> "★"
        MoveQuality.EXCELLENT -> "!"
        MoveQuality.GOOD -> "✓"
        MoveQuality.BOOK -> "≡"
        else -> b.quality.symbol
    }
    p.text(this, label, origin.x, origin.y + p.dp(5f) * scale, 14f * scale, Color.White, alpha = fade, center = true)
    if (t > 0.2f) {
        p.pill(this, Offset(origin.x, origin.y - r - p.dp(2f)), b.quality.label, null, col, Color.White, popScale(t - 0.2f) * 0.8f, fade, pointer = false, titleSp = 11f)
    }
}

/** Tracks large evaluation swings to spawn sparks from the eval bar. */
private class EvalJump {
    var last = Float.NaN
    var burstNs = 0L
    var burstUp = true

    fun update(target: Float, now: Long) {
        if (!last.isNaN() && abs(target - last) > 0.12f) {
            burstNs = now
            burstUp = target > last
        }
        last = target
    }
}

/**
 * A glassy evaluation bar hovering beside the board's left edge, leaning with the board's
 * perspective, with a soft shadow, a glowing white/black split and sparks on big swings.
 */
private fun DrawScope.drawFlyingEvalBar(p: Fx, pj: ArProjector, win: Float, model: OverlayModel, now: Long, jump: EvalJump) {
    jump.update(model.whiteWin, now)
    val c = pj.corners
    val edges = (0..3).map { c[it] to c[(it + 1) % 4] }
    val center = Offset(c.sumOf { it.x.toDouble() }.toFloat() / 4, c.sumOf { it.y.toDouble() }.toFloat() / 4)
    // The edge whose midpoint is left-most on screen.
    val (ea, eb) = edges.minBy { (it.first.x + it.second.x) / 2 }
    val mid = Offset((ea.x + eb.x) / 2, (ea.y + eb.y) / 2)
    var dir = eb - ea
    val len = dir.getDistance()
    dir /= max(len, 1f)
    if (dir.y > 0) dir = -dir // point "up" the screen
    var normal = Offset(-dir.y, dir.x)
    if ((center - mid).let { it.x * normal.x + it.y * normal.y } > 0) normal = -normal // away from the board
    val t = now / 1e9f
    val hover = sin(t * 1.7f) * p.dp(5f)
    val barLen = (len * 0.82f).coerceIn(p.dp(130f), p.dp(320f))
    val barW = p.dp(16f)
    val anchor = mid + normal * p.dp(30f) + Offset(0f, hover)
    if (anchor.x < barW) return
    val angle = (atan2(dir.y, dir.x) * 180f / PI.toFloat()) + 90f

    // Which end of the bar is White's side on screen.
    val whiteCenter = pj.chess(4f, 0.5f)
    val blackCenter = pj.chess(4f, 7.5f)
    val whiteAtBottom = whiteCenter.y >= blackCenter.y

    // Shadow under the hovering bar.
    val shadowC = Offset(anchor.x, anchor.y + barLen / 2 + p.dp(16f) - hover)
    val shadowScale = 1f - hover / p.dp(40f)
    drawOval(Color.Black.copy(alpha = 0.28f), topLeft = Offset(shadowC.x - barW * 1.2f * shadowScale, shadowC.y - p.dp(4f)), size = androidx.compose.ui.geometry.Size(barW * 2.4f * shadowScale, p.dp(8f)))

    rotate(angle, pivot = anchor) {
        val left = anchor.x - barW / 2
        val top = anchor.y - barLen / 2
        val track = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + barW, top + barLen, barW / 2, barW / 2)) }
        p.fill(this, track, Lens.Neon, 0.35f, glow = p.dp(14f))
        p.fill(this, track, Lens.EvalBlack, 0.92f)
        val whiteFrac = win.coerceIn(0.02f, 0.98f)
        val wTop = if (whiteAtBottom) top + barLen * (1 - whiteFrac) else top
        val wBottom = if (whiteAtBottom) top + barLen else top + barLen * whiteFrac
        clipRect(left - 1f, wTop, left + barW + 1f, wBottom) {
            p.fill(this, track, Lens.EvalWhite, 1f)
        }
        val splitY = if (whiteAtBottom) top + barLen * (1 - whiteFrac) else top + barLen * whiteFrac
        p.line(this, Offset(left - p.dp(3f), splitY), Offset(left + barW + p.dp(3f), splitY), Lens.Neon, p.dp(2.5f), 1f, glow = p.dp(8f))
        p.stroke(this, track, Color.White, p.dp(1.2f), 0.55f)
        // Tick at equality.
        val midY = top + barLen / 2
        p.line(this, Offset(left, midY), Offset(left + barW * 0.35f, midY), Lens.Neon, p.dp(1f), 0.7f)
        // Sparks on big swings.
        val since = (now - jump.burstNs) / 1e9f
        if (jump.burstNs != 0L && since < 1f) p.burst(this, Offset(anchor.x, splitY), since, Lens.Neon, count = 18, speed = p.dp(110f), seed = (jump.burstNs % 997).toInt())
    }
    // Label floating above the bar (kept upright).
    val topEnd = anchor + dir * (barLen / 2 + p.dp(6f))
    if (model.evalText.isNotEmpty()) {
        val favoured = if (model.whiteWin >= 0.5f) Lens.EvalWhite else Lens.EvalBlack
        val fg = if (model.whiteWin >= 0.5f) Lens.EvalBlack else Lens.EvalWhite
        p.pill(this, topEnd, model.evalText, if (model.depth > 0) "d${model.depth}" else null, favoured, fg, 1f, 1f, pointer = true, titleSp = 13f)
    }
}
