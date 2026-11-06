package com.chesslens.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds a chessboard in a grayscale frame.
 *
 * 1. Collects convex quadrilateral candidates from edge and threshold contours.
 * 2. Rectifies each candidate and searches the inset (board border) that maximises the
 *    light/dark checker contrast of the 8x8 grid.
 * 3. Returns the best scoring grid quad.
 */
class BoardDetector(private val workSize: Int = 400) {

    data class Result(val quad: BoardQuad, val score: Double)

    fun detect(gray: Mat): Result? {
        val scale = minOf(1.0, workSize.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val blur = Mat()
        Imgproc.GaussianBlur(small, blur, Size(5.0, 5.0), 0.0)

        val candidates = ArrayList<BoardQuad>()
        val imgArea = small.cols().toDouble() * small.rows()

        // (a) Canny edges.
        val edges = Mat()
        Imgproc.Canny(blur, edges, 30.0, 90.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        collectQuads(edges, imgArea, candidates)

        // (b) Otsu and adaptive thresholds (both polarities).
        val bin = Mat()
        Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        collectQuads(bin, imgArea, candidates)
        Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        collectQuads(bin, imgArea, candidates)
        Imgproc.adaptiveThreshold(blur, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 51, 0.0)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
        collectQuads(bin, imgArea, candidates)

        // (c) The whole frame is always a candidate (board filling the view).
        val w = small.cols().toFloat()
        val h = small.rows().toFloat()
        candidates += BoardQuad(listOf(Pt(0f, 0f), Pt(w - 1, 0f), Pt(w - 1, h - 1), Pt(0f, h - 1)))

        var best: Result? = null
        for (q in dedupe(candidates)) {
            val r = refine(blur, q) ?: continue
            if (best == null || r.score > best.score) best = r
        }
        // Re-fit using the grid itself as the candidate: it is a true rectangle on the board
        // plane, so the axis-aligned lattice model becomes exact and corners converge.
        repeat(3) {
            val cur = best ?: return@repeat
            val again = refine(blur, cur.quad) ?: return@repeat
            if (again.score >= cur.score - 0.02) best = again
        }
        var result = best
        if (result != null && result.score >= MIN_SCORE) {
            // Corner polish: lets the grid rotate/shear onto the true grid lines.
            val field = GradField.of(blur)
            val polished = polish(field, result.quad)
            val rescored = refineScore(blur, polished)
            if (rescored >= MIN_SCORE) result = Result(polished, rescored)
        }
        small.release(); blur.release(); edges.release(); bin.release(); kernel.release()
        val b = result ?: return null
        if (b.score < MIN_SCORE) return null
        val inv = (1.0 / scale).toFloat()
        return Result(b.quad.map { Pt(it.x * inv, it.y * inv) }, b.score)
    }

    /** Checker score of [grid] taken as-is (no lattice search). */
    private fun refineScore(img: Mat, grid: BoardQuad): Double {
        val region = grid.inset(-MARGIN, -MARGIN, -MARGIN, -MARGIN)
        val warped = CvUtils.warpBoard(img, region, WARP)
        val px = CvUtils.bytes(warped)
        warped.release()
        val a = WARP * MARGIN / (1 + 2 * MARGIN)
        val c = (WARP - 2 * a) / 8f
        return gridScore(Integral(WARP, WARP, px), a, c, a, c)
    }

    /** Image gradients used to score grid-line alignment directly in image space. */
    internal class GradField(private val w: Int, private val h: Int, private val gx: FloatArray, private val gy: FloatArray) {
        fun along(x: Float, y: Float, nx: Float, ny: Float): Float {
            if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return 0f
            val xi = x.toInt()
            val yi = y.toInt()
            val fx = x - xi
            val fy = y - yi
            val i = yi * w + xi
            fun lerp(a: FloatArray): Float {
                val top = a[i] * (1 - fx) + a[i + 1] * fx
                val bot = a[i + w] * (1 - fx) + a[i + w + 1] * fx
                return top * (1 - fy) + bot * fy
            }
            return abs(lerp(gx) * nx + lerp(gy) * ny)
        }

        companion object {
            fun of(img: Mat): GradField {
                val gx = Mat()
                val gy = Mat()
                Imgproc.Sobel(img, gx, CvType.CV_32F, 1, 0, 3)
                Imgproc.Sobel(img, gy, CvType.CV_32F, 0, 1, 3)
                val n = img.cols() * img.rows()
                val ax = FloatArray(n)
                val ay = FloatArray(n)
                gx.get(0, 0, ax)
                gy.get(0, 0, ay)
                gx.release(); gy.release()
                return GradField(img.cols(), img.rows(), ax, ay)
            }
        }
    }

    /** Mean gradient across the 18 grid lines minus across the 16 mid-cell lines. */
    private fun lineScore(f: GradField, q: BoardQuad): Double {
        val h = q.boardToImage
        fun line(t: Float, vertical: Boolean): Double {
            var sum = 0.0
            val n = 40
            for (i in 0 until n) {
                val sPos = 0.15f + 7.7f * i / (n - 1)
                val p = if (vertical) h.map(t, sPos) else h.map(sPos, t)
                val p2 = if (vertical) h.map(t, sPos + 0.05f) else h.map(sPos + 0.05f, t)
                val dx = p2.x - p.x
                val dy = p2.y - p.y
                val len = kotlin.math.hypot(dx, dy)
                if (len < 1e-6f) continue
                sum += f.along(p.x, p.y, -dy / len, dx / len)
            }
            return sum / n
        }
        var on = 0.0
        var off = 0.0
        for (k in 0..8) on += line(k.toFloat(), true) + line(k.toFloat(), false)
        for (k in 0..7) off += line(k + 0.5f, true) + line(k + 0.5f, false)
        return on / 18 - off / 16
    }

    private fun polish(f: GradField, start: BoardQuad): BoardQuad {
        var cur = start.corners.toMutableList()
        var best = lineScore(f, start)
        for (step in floatArrayOf(3f, 1.5f, 0.75f, 0.4f)) {
            var rounds = 0
            var improved = true
            while (improved && rounds++ < 6) {
                improved = false
                for (c in 0..3) for (axis in 0..1) for (dir in intArrayOf(-1, 1)) {
                    val trial = cur.toMutableList()
                    val p = trial[c]
                    trial[c] = if (axis == 0) Pt(p.x + dir * step, p.y) else Pt(p.x, p.y + dir * step)
                    val q = BoardQuad(trial)
                    if (!q.isConvex()) continue
                    val s = lineScore(f, q)
                    if (s > best) {
                        best = s
                        cur = trial
                        improved = true
                    }
                }
            }
        }
        return BoardQuad(cur)
    }

    private fun collectQuads(binary: Mat, imgArea: Double, out: MutableList<BoardQuad>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.06) { c.release(); continue }
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hullPts = hullIdx.toArray().map { pts[it] }
            hullIdx.release()
            c.release()
            if (hullPts.size < 4) continue
            val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
            val peri = Imgproc.arcLength(hull2f, true)
            var eps = 0.015
            while (eps <= 0.1) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                val a = approx.toArray()
                approx.release()
                if (a.size == 4) {
                    val q = BoardQuad.ordered(a.map { Pt(it.x.toFloat(), it.y.toFloat()) })
                    if (q.isConvex() && q.area() > imgArea * 0.06 && plausibleShape(q)) out += q
                    break
                }
                if (a.size < 4) break
                eps += 0.01
            }
            hull2f.release()
        }
    }

    /** Rejects slivers: every side must be a reasonable fraction of the longest one. */
    private fun plausibleShape(q: BoardQuad): Boolean {
        val sides = (0..3).map { q.corners[it].dist(q.corners[(it + 1) % 4]) }
        return sides.min() > sides.max() * 0.25f
    }

    private fun dedupe(list: List<BoardQuad>): List<BoardQuad> {
        val out = ArrayList<BoardQuad>()
        for (q in list.sortedByDescending { it.area() }) {
            if (out.none { it.maxCornerDistance(q) < 4f }) out += q
        }
        return out.take(14)
    }

    /**
     * Rectifies [q] (with a margin around it) and fits the 9x9 grid-line lattice: vertical and
     * horizontal gradient projections give candidate line spacings/offsets, which are verified
     * with the light/dark checker contrast and a penalty if the alternation continues outside
     * the grid (= lattice shifted by a square). Returns the 8x8 grid quad in [img] coordinates.
     */
    fun refine(img: Mat, q: BoardQuad): Result? {
        val region = q.inset(-MARGIN, -MARGIN, -MARGIN, -MARGIN)
        if (!region.isConvex()) return null
        val warped = CvUtils.warpBoard(img, region, WARP)
        val px = CvUtils.bytes(warped)
        val std = stdDev(px)
        if (std < 4.0) { warped.release(); return null }

        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(warped, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(warped, gy, CvType.CV_32F, 0, 1, 3)
        val fx = FloatArray(WARP * WARP)
        val fy = FloatArray(WARP * WARP)
        gx.get(0, 0, fx)
        gy.get(0, 0, fy)
        gx.release(); gy.release(); warped.release()

        // Candidate occupies [a, b] in warped pixels.
        val a = WARP * MARGIN / (1 + 2 * MARGIN)
        val b = WARP - a
        val band0 = (a - (b - a) * 0.1f).toInt().coerceAtLeast(0)
        val band1 = (b + (b - a) * 0.1f).toInt().coerceAtMost(WARP)
        val profX = DoubleArray(WARP)
        val profY = DoubleArray(WARP)
        for (y in band0 until band1) for (x in 0 until WARP) profX[x] += abs(fx[y * WARP + x]).toDouble()
        for (y in 0 until WARP) for (x in band0 until band1) profY[y] += abs(fy[y * WARP + x]).toDouble()
        val sx = smooth(profX)
        val sy = smooth(profY)
        val nominal = (b - a) / 8f
        val xs = lattices(sx, nominal)
        val ys = lattices(sy, nominal)
        if (xs.isEmpty() || ys.isEmpty()) return null

        val integral = Integral(WARP, WARP, px)
        var best: FloatArray? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (lx in xs) for (ly in ys) {
            val s = gridScore(integral, lx[0], lx[1], ly[0], ly[1])
            if (s > bestScore) {
                bestScore = s
                best = floatArrayOf(lx[0], lx[1], ly[0], ly[1])
            }
        }
        val g = best ?: return null
        val toImg = { x: Float, y: Float -> region.boardToImage.map(x / WARP * 8f, y / WARP * 8f) }
        val grid = BoardQuad(
            listOf(
                toImg(g[0], g[2]),
                toImg(g[0] + 8 * g[1], g[2]),
                toImg(g[0] + 8 * g[1], g[2] + 8 * g[3]),
                toImg(g[0], g[2] + 8 * g[3]),
            ),
        )
        if (!grid.isConvex()) return null
        return Result(grid, bestScore)
    }

    private fun smooth(p: DoubleArray): DoubleArray {
        val k = doubleArrayOf(0.06, 0.24, 0.4, 0.24, 0.06)
        return DoubleArray(p.size) { i ->
            var s = 0.0
            for (j in -2..2) s += p[(i + j).coerceIn(0, p.size - 1)] * k[j + 2]
            s
        }
    }

    private fun sample(p: DoubleArray, x: Float): Double {
        if (x < 0 || x > p.size - 1) return 0.0
        val i = x.toInt()
        val f = x - i
        return if (i + 1 < p.size) p[i] * (1 - f) + p[i + 1] * f else p[i]
    }

    /** Top distinct (offset, spacing) pairs of 9 equally spaced lines in profile [p]. */
    private fun lattices(p: DoubleArray, nominal: Float): List<FloatArray> {
        val found = ArrayList<Pair<Double, FloatArray>>()
        var c = nominal * 0.6f
        while (c <= nominal * 1.45f) {
            var x0 = 0f
            while (x0 + 8 * c <= p.size - 1) {
                var on = 0.0
                for (k in 0..8) on += sample(p, x0 + k * c)
                var off = 0.0
                for (k in 0..7) off += sample(p, x0 + (k + 0.5f) * c)
                found += (on / 9 - off / 8) to floatArrayOf(x0, c)
                x0 += 0.5f
            }
            c += 0.25f
        }
        found.sortByDescending { it.first }
        val out = ArrayList<FloatArray>()
        for ((_, l) in found) {
            if (out.none { abs(it[0] - l[0]) < l[1] * 0.3f && abs(it[1] - l[1]) < l[1] * 0.08f }) out += l
            if (out.size >= 4) break
        }
        return out
    }

    /** Checker contrast of the 8x8 grid minus a penalty when alternation continues outside it. */
    private fun gridScore(ig: Integral, x0: Float, cx: Float, y0: Float, cy: Float): Double {
        val patch = 0.6f
        val means = Array(10) { DoubleArray(10) }
        val valid = Array(10) { BooleanArray(10) }
        for (j in -1..8) for (i in -1..8) {
            val ccx = x0 + (i + 0.5f) * cx
            val ccy = y0 + (j + 0.5f) * cy
            val hw = cx * patch / 2
            val hh = cy * patch / 2
            val inside = ccx - hw >= 0 && ccy - hh >= 0 && ccx + hw <= WARP && ccy + hh <= WARP
            valid[j + 1][i + 1] = inside
            means[j + 1][i + 1] = if (inside) ig.mean((ccx - hw).toInt(), (ccy - hh).toInt(), (ccx + hw).toInt(), (ccy + hh).toInt()) else 0.0
        }
        fun m(i: Int, j: Int) = means[j + 1][i + 1]
        var even = 0.0
        var odd = 0.0
        for (j in 0..7) for (i in 0..7) if ((i + j) % 2 == 0) even += m(i, j) else odd += m(i, j)
        val polarity = if (even >= odd) 1.0 else -1.0
        val local = ArrayList<Double>(49)
        for (j in 0..6) for (i in 0..6) {
            val d = (m(i, j) + m(i + 1, j + 1) - m(i + 1, j) - m(i, j + 1)) / 2
            local += d * (if ((i + j) % 2 == 0) 1.0 else -1.0) * polarity
        }
        local.sort()
        val contrast = 0.5 * abs(even - odd) / 32.0 + 0.5 * local[local.size / 3]

        // Continuation penalty: does the row/column just outside the grid keep alternating?
        // (A plain border scores ~0, real squares score ~contrast/2.)
        fun parity(i: Int, j: Int) = (if (((i + j) % 2 + 2) % 2 == 0) 1.0 else -1.0) * polarity
        var penalty = 0.0
        for (side in 0..3) {
            var sum = 0.0
            var n = 0
            var mean = 0.0
            val cells = (0..7).map { k ->
                when (side) {
                    0 -> -1 to k
                    1 -> 8 to k
                    2 -> k to -1
                    else -> k to 8
                }
            }
            for ((i, j) in cells) if (valid[j + 1][i + 1]) { mean += m(i, j); n++ }
            if (n < 4) continue
            mean /= n
            for ((i, j) in cells) if (valid[j + 1][i + 1]) sum += parity(i, j) * (m(i, j) - mean)
            penalty += max(0.0, 2.0 * sum / n)
        }
        // Normalise by the spread of the cell means so scores are comparable across candidates
        // (a perfect checkerboard scores ~2 regardless of lighting).
        var mu = 0.0
        for (j in 0..7) for (i in 0..7) mu += m(i, j)
        mu /= 64
        var v = 0.0
        for (j in 0..7) for (i in 0..7) v += (m(i, j) - mu) * (m(i, j) - mu)
        val spread = sqrt(v / 64)
        return (contrast - penalty) / (spread + 4.0)
    }

    private fun stdDev(px: ByteArray): Double {
        var s = 0.0
        var s2 = 0.0
        for (b in px) {
            val v = (b.toInt() and 0xFF).toDouble()
            s += v
            s2 += v * v
        }
        val n = px.size.toDouble()
        val mean = s / n
        return sqrt(max(0.0, s2 / n - mean * mean))
    }

    companion object {
        private const val WARP = 320
        private const val MARGIN = 0.22f
        const val MIN_SCORE = 0.6
    }
}

internal fun Point.toPt() = Pt(x.toFloat(), y.toFloat())
