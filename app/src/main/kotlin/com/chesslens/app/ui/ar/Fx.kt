package com.chesslens.app.ui.ar

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Neon drawing helpers on the native canvas (blur mask glow, text, pills, particles). */
class Fx(
    private val density: Float,
    private val display: Typeface = Typeface.DEFAULT_BOLD,
    private val body: Typeface = Typeface.DEFAULT,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = display }
    private val blurs = HashMap<Int, BlurMaskFilter>()
    private val rect = RectF()

    fun dp(v: Float) = v * density

    private fun blur(r: Float): BlurMaskFilter? {
        val k = r.roundToInt()
        if (k < 1) return null
        return blurs.getOrPut(k) { BlurMaskFilter(k.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    private fun prep(color: Color, alpha: Float, glow: Float, style: Paint.Style, width: Float = 0f) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = style
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb()
        paint.maskFilter = blur(glow)
    }

    fun fill(s: DrawScope, path: Path, color: Color, alpha: Float = 1f, glow: Float = 0f) {
        prep(color, alpha, glow, Paint.Style.FILL)
        s.drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }

    fun stroke(s: DrawScope, path: Path, color: Color, width: Float, alpha: Float = 1f, glow: Float = 0f) {
        prep(color, alpha, glow, Paint.Style.STROKE, width)
        s.drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }

    fun line(s: DrawScope, a: Offset, b: Offset, color: Color, width: Float, alpha: Float = 1f, glow: Float = 0f) {
        prep(color, alpha, glow, Paint.Style.STROKE, width)
        s.drawContext.canvas.nativeCanvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    fun circle(s: DrawScope, c: Offset, r: Float, color: Color, alpha: Float = 1f, glow: Float = 0f, strokeWidth: Float = 0f) {
        prep(color, alpha, glow, if (strokeWidth > 0) Paint.Style.STROKE else Paint.Style.FILL, strokeWidth)
        s.drawContext.canvas.nativeCanvas.drawCircle(c.x, c.y, r, paint)
    }

    fun textWidth(str: String, sizeSp: Float, bold: Boolean = true): Float {
        text.textSize = dp(sizeSp)
        text.typeface = if (bold) display else body
        return text.measureText(str)
    }

    fun text(s: DrawScope, str: String, x: Float, baseline: Float, sizeSp: Float, color: Color, bold: Boolean = true, alpha: Float = 1f, center: Boolean = false) {
        text.textSize = dp(sizeSp)
        text.typeface = if (bold) display else body
        text.color = color.copy(alpha = color.alpha * alpha).toArgb()
        text.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        s.drawContext.canvas.nativeCanvas.drawText(str, x, baseline, text)
    }

    /** Width and height (including the pointer) of a [pill]. */
    fun pillSize(title: String, sub: String?, titleSp: Float = 15f, pointer: Boolean = true): Pair<Float, Float> {
        val subSp = titleSp * 0.72f
        val w = textWidth(title, titleSp) + (if (sub != null) textWidth(sub, subSp, bold = false) + dp(5f) else 0f) + dp(20f)
        val h = dp(titleSp + 13f) + (if (pointer) dp(6f) else 0f)
        return w to h
    }

    /**
     * Floating label like "Nf6 +0.26" centred above [anchor]. [scale] animates pop-in.
     * Returns the pill's bounds height for stacking.
     */
    fun pill(
        s: DrawScope,
        anchor: Offset,
        title: String,
        sub: String?,
        bg: Color,
        fg: Color,
        scale: Float = 1f,
        alpha: Float = 1f,
        pointer: Boolean = true,
        titleSp: Float = 15f,
    ) {
        if (alpha <= 0.01f || scale <= 0.01f) return
        val subSp = titleSp * 0.72f
        val pad = dp(10f)
        val gap = dp(5f)
        val tw = textWidth(title, titleSp)
        val sw = if (sub != null) textWidth(sub, subSp, bold = false) + gap else 0f
        val w = tw + sw + pad * 2
        val h = dp(titleSp + 13f)
        val c = s.drawContext.canvas.nativeCanvas
        c.save()
        c.translate(anchor.x, anchor.y)
        c.scale(scale, scale)
        val top = -h - (if (pointer) dp(6f) else 0f)
        rect.set(-w / 2, top, w / 2, top + h)
        // Glow
        prep(bg, alpha * 0.55f, dp(12f), Paint.Style.FILL)
        c.drawRoundRect(rect, h / 2, h / 2, paint)
        prep(bg, alpha, 0f, Paint.Style.FILL)
        c.drawRoundRect(rect, h / 2, h / 2, paint)
        if (pointer) {
            val p = android.graphics.Path()
            p.moveTo(-dp(6f), top + h - 1)
            p.lineTo(dp(6f), top + h - 1)
            p.lineTo(0f, top + h + dp(6f))
            p.close()
            c.drawPath(p, paint)
        }
        prep(Color.White, alpha * 0.35f, 0f, Paint.Style.STROKE, dp(1f))
        c.drawRoundRect(rect, h / 2, h / 2, paint)
        val baseline = top + h / 2 + dp(titleSp) * 0.36f
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(titleSp)
        text.typeface = display
        text.color = fg.copy(alpha = alpha).toArgb()
        c.drawText(title, -w / 2 + pad, baseline, text)
        if (sub != null) {
            text.textSize = dp(subSp)
            text.typeface = body
            text.color = fg.copy(alpha = alpha * 0.72f).toArgb()
            c.drawText(sub, -w / 2 + pad + tw + gap, baseline, text)
        }
        c.restore()
    }

    /** Radial spark burst; [t] seconds since spawn. */
    fun burst(s: DrawScope, origin: Offset, t: Float, color: Color, count: Int = 14, speed: Float = dp(140f), life: Float = 0.9f, seed: Int = 0) {
        if (t < 0 || t > life) return
        val k = 1f - t / life
        for (i in 0 until count) {
            val a = (2 * PI * (i + hash(seed + i) * 0.6) / count).toFloat()
            val v = speed * (0.55f + hash(seed * 31 + i) * 0.7f)
            val x = origin.x + cos(a) * v * t
            val y = origin.y + sin(a) * v * t + 0.5f * dp(260f) * t * t
            circle(s, Offset(x, y), dp(2.2f) * (0.4f + k), color, alpha = k, glow = dp(4f))
        }
    }

    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }
}

/** Geometry for move ribbons in chess coordinates. */
object Ribbon {
    /** Polyline from square centre to square centre; knights take an L with a rounded elbow. */
    fun movePath(from: Int, to: Int, knight: Boolean): List<Offset> {
        val a = Offset((from and 7) + 0.5f, (from shr 3) + 0.5f)
        val b = Offset((to and 7) + 0.5f, (to shr 3) + 0.5f)
        if (!knight) return listOf(a, b)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val elbow = if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) Offset(a.x, b.y) else Offset(b.x, a.y)
        val r = 0.3f
        val inDir = norm(elbow - a)
        val outDir = norm(b - elbow)
        val p1 = elbow - inDir * r
        val p2 = elbow + outDir * r
        val pts = ArrayList<Offset>()
        pts += a
        for (i in 0..6) {
            val t = i / 6f
            val q = p1 * ((1 - t) * (1 - t)) + elbow * (2 * (1 - t) * t) + p2 * (t * t)
            pts += q
        }
        pts += b
        return pts
    }

    private fun norm(v: Offset): Offset {
        val l = hypot(v.x, v.y)
        return if (l < 1e-6f) Offset(0f, 0f) else Offset(v.x / l, v.y / l)
    }

    fun length(pts: List<Offset>): Float {
        var l = 0f
        for (i in 1 until pts.size) l += (pts[i] - pts[i - 1]).getDistance()
        return l
    }

    /** Point at arc length [d] along the polyline. */
    fun pointAt(pts: List<Offset>, d: Float): Offset {
        var rem = d
        for (i in 1 until pts.size) {
            val seg = (pts[i] - pts[i - 1]).getDistance()
            if (rem <= seg) return pts[i - 1] + (pts[i] - pts[i - 1]) * (rem / max(seg, 1e-6f))
            rem -= seg
        }
        return pts.last()
    }

    /** Sub-polyline between arc lengths [d0] and [d1], resampled at roughly [step]. */
    fun slice(pts: List<Offset>, d0: Float, d1: Float, step: Float = 0.12f): List<Offset> {
        if (d1 <= d0) return emptyList()
        val out = ArrayList<Offset>()
        var d = d0
        while (d < d1) {
            out += pointAt(pts, d)
            d += step
        }
        out += pointAt(pts, d1)
        // Keep original corners for crisp elbows.
        return out
    }

    /**
     * Filled arrow outline (chess coords) following [pts]: body of [width] from [startInset]
     * to the tip at [progress] of the total length, with an arrow head.
     */
    fun arrow(pts: List<Offset>, width: Float, headWidth: Float, headLength: Float, progress: Float = 1f, startInset: Float = 0.25f, endInset: Float = 0.1f): List<Offset> {
        val total = length(pts)
        val start = startInset.coerceAtMost(total * 0.4f)
        val end = (total - endInset).coerceAtLeast(start + 0.05f)
        val tipD = start + (end - start) * progress.coerceIn(0f, 1f)
        val headD = (tipD - headLength).coerceAtLeast(start)
        val body = slice(pts, start, headD)
        val left = ArrayList<Offset>()
        val right = ArrayList<Offset>()
        for (i in body.indices) {
            val prev = body[maxOf(0, i - 1)]
            val next = body[minOf(body.size - 1, i + 1)]
            val t = norm(next - prev)
            val n = Offset(-t.y, t.x)
            left += body[i] + n * (width / 2)
            right += body[i] - n * (width / 2)
        }
        val tip = pointAt(pts, tipD)
        val base = pointAt(pts, headD)
        var dir = norm(tip - pointAt(pts, (tipD - 0.08f).coerceAtLeast(0f)))
        if (dir == Offset(0f, 0f)) dir = norm(pts.last() - pts[pts.size - 2])
        val n = Offset(-dir.y, dir.x)
        val hw = headWidth / 2 * (if (tipD - start < headLength) (tipD - start) / headLength else 1f)
        val out = ArrayList<Offset>()
        out += left
        out += base + n * hw
        out += tip
        out += base - n * hw
        out += right.reversed()
        return out
    }
}

