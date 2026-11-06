package com.chesslens.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the board from its interior X-corners (where four squares meet) instead of its outline:
 *
 * 1. ChESS saddle detector (Bennett & Lasenby) + sub-pixel refinement.
 * 2. RANSAC lattice fit: a unit cell seeds a homography from integer lattice coordinates to the
 *    image, which grows by snapping predicted corners to detections and refitting.
 * 3. The 8x8 window is placed on the lattice where interior corners, the checker pattern and the
 *    board's plain frame agree (or nearest to the previous lock when tracking).
 *
 * Works with pieces occluding corners, a fold seam across the board, and boards that run off
 * the edge of the frame (corners may lie outside the image).
 */
class GridLocator(private val workSize: Int = 400) {

    /** [orientation] is set when the expected position decided it (see [locate]). */
    data class Result(val quad: BoardQuad, val inliers: Int, val score: Double, val orientation: Int? = null)

    private data class Lattice(val h: Homography, val coords: List<IntArray>, val pts: List<Pt>)

    /**
     * @param prior the previous lock while tracking: the window nearest to it is kept
     * @param expected pieces (a1 = 0) the board should show: picks the window and orientation
     *   whose occupancy and piece colours match best (used when locking)
     */
    var debugWindows = ""

    /** Per-stage timings of the last call (debugging). */
    var lastTimings = ""
        private set

    fun locate(gray: Mat, prior: BoardQuad? = null, expected: IntArray? = null): Result? {
        val t0 = System.nanoTime()
        val scale = minOf(1.0, workSize.toDouble() / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        val w = small.cols()
        val h = small.rows()
        val px = CvUtils.bytes(small)
        val response = chess(px, w, h)
        val corners = saddles(response, w, h)
        if (corners.size < 9) {
            small.release()
            return null
        }
        val t1 = System.nanoTime()
        val refined = subPix(small, corners)
        val t2 = System.nanoTime()
        val s = scale.toFloat()
        val priorSmall = prior?.map { Pt(it.x * s, it.y * s) }
        // While tracking, the previous lock predicts the lattice: no search needed.
        val lattice = priorSmall?.let { fitFromPrior(refined, it) } ?: fitLattice(refined)
        val t3 = System.nanoTime()
        lastTimings = "pre=${(t1 - t0) / 1000000} subpix=${(t2 - t1) / 1000000} lattice=${(t3 - t2) / 1000000} n=${corners.size}"
        if (lattice == null || lattice.coords.size < 10) {
            small.release()
            return null
        }
        val placed0 = placeWindow(lattice, small, px, w, h, response, priorSmall, expected, refined)
        small.release()
        placed0 ?: return null
        val t4 = System.nanoTime()
        val placed = refitInterior(lattice, placed0)
        lastTimings += " place=${(t4 - t3) / 1000000} refit=${(System.nanoTime() - t4) / 1000000}"
        val inv = 1f / s
        return Result(placed.quad.map { Pt(it.x * inv, it.y * inv) }, lattice.coords.size, placed.score, placed.orientation)
    }

    private class Placed(val quad: BoardQuad, val score: Double, val orientation: Int?)

    /**
     * Refits the board corners from the inliers that are true interior corners of the chosen
     * 8x8 window only (corners picked up outside the board would bias and destabilise it).
     */
    private fun refitInterior(l: Lattice, p: Placed): Placed {
        val toBoard = p.quad.imageToBoard
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (k in l.pts.indices) {
            val b = toBoard.map(l.pts[k])
            val u = b.x.roundToInt()
            val v = b.y.roundToInt()
            if (u in 1..7 && v in 1..7 && abs(b.x - u) < 0.3f && abs(b.y - v) < 0.3f) {
                src += Point(u.toDouble(), v.toDouble())
                dst += Point(l.pts[k].x.toDouble(), l.pts[k].y.toDouble())
            }
        }
        if (src.size < 8) return p
        // Least squares, then drop corners that disagree (stray detections near a lattice
        // point) and refit, so a single bad corner can't drag the whole board.
        var keepSrc: List<Point> = src
        var keepDst: List<Point> = dst
        var hm: Homography? = null
        repeat(3) {
            val m = Calib3d.findHomography(MatOfPoint2f(*keepSrc.toTypedArray()), MatOfPoint2f(*keepDst.toTypedArray()), 0, 0.0)
            if (m.empty()) return p
            val d = DoubleArray(9)
            m.convertTo(m, CvType.CV_64F)
            m.get(0, 0, d)
            m.release()
            val h = Homography(d)
            hm = h
            val cell = h.map(4f, 4f).dist(h.map(5f, 4f))
            val tol = max(0.8f, cell * 0.06f)
            val ok = keepSrc.indices.filter { i ->
                h.map(keepSrc[i].x.toFloat(), keepSrc[i].y.toFloat()).dist(Pt(keepDst[i].x.toFloat(), keepDst[i].y.toFloat())) <= tol
            }
            if (ok.size == keepSrc.size || ok.size < 8) return@repeat
            keepSrc = ok.map { keepSrc[it] }
            keepDst = ok.map { keepDst[it] }
        }
        val h = hm ?: return p
        val q = BoardQuad(BoardQuad.BOARD_CORNERS.map { h.map(it) })
        return if (q.isConvex()) Placed(q, p.score, p.orientation) else p
    }

    // ---------------------------------------------------------------------------- ChESS

    private val ring: Array<IntArray> = Array(16) { k ->
        val a = Math.PI * 2 * k / 16
        intArrayOf((RADIUS * cos(a)).roundToInt(), (RADIUS * sin(a)).roundToInt())
    }

    /** ChESS response: positive and large at X-corners, negative on edges and blobs. */
    private fun chess(px: ByteArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val v = IntArray(16)
        val off = IntArray(16) { ring[it][1] * w + ring[it][0] }
        for (y in RADIUS + 1 until h - RADIUS - 1) {
            for (x in RADIUS + 1 until w - RADIUS - 1) {
                val c = y * w + x
                var sum = 0
                for (k in 0 until 16) {
                    v[k] = px[c + off[k]].toInt() and 0xFF
                    sum += v[k]
                }
                var sr = 0
                for (n in 0 until 4) sr += abs(v[n] + v[n + 8] - v[n + 4] - v[n + 12])
                var dr = 0
                for (n in 0 until 8) dr += abs(v[n] - v[n + 8])
                var local = 0
                for (dy in -1..1) for (dx in -1..1) local += px[c + dy * w + dx].toInt() and 0xFF
                val mr = abs(sum / 16f - local / 9f) * 16f
                out[c] = sr - dr - mr
            }
        }
        return out
    }

    /** Local maxima of the response above an adaptive threshold. */
    private fun saddles(r: FloatArray, w: Int, h: Int): List<Pt> {
        var maxR = 0f
        for (v in r) if (v > maxR) maxR = v
        val thr = max(90f, maxR * 0.12f)
        val out = ArrayList<Pair<Float, Pt>>()
        val nms = 3
        for (y in nms until h - nms) {
            for (x in nms until w - nms) {
                val v = r[y * w + x]
                if (v < thr) continue
                var isMax = true
                loop@ for (dy in -nms..nms) for (dx in -nms..nms) {
                    if (dx == 0 && dy == 0) continue
                    val o = r[(y + dy) * w + x + dx]
                    if (o > v || (o == v && (dy < 0 || (dy == 0 && dx < 0)))) { isMax = false; break@loop }
                }
                if (isMax) out += v to Pt(x.toFloat(), y.toFloat())
            }
        }
        return out.sortedByDescending { it.first }.take(MAX_POINTS).map { it.second }
    }

    private fun subPix(img: Mat, pts: List<Pt>): List<Pt> {
        val m = MatOfPoint2f(*pts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        Imgproc.cornerSubPix(img, m, Size(3.0, 3.0), Size(-1.0, -1.0), TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 20, 0.03))
        val out = m.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) }
        m.release()
        return out
    }

    // ---------------------------------------------------------------------------- lattice

    private class PointIndex(pts: List<Pt>, private val cell: Float) {
        private val map = HashMap<Long, MutableList<Int>>()
        private val pts = pts

        init {
            pts.forEachIndexed { i, p -> map.getOrPut(key((p.x / cell).toInt(), (p.y / cell).toInt())) { ArrayList() } += i }
        }

        private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

        fun nearest(p: Pt, maxDist: Float): Int {
            if (!p.x.isFinite() || !p.y.isFinite() || !maxDist.isFinite()) return -1
            val cx = (p.x / cell).toInt()
            val cy = (p.y / cell).toInt()
            val reach = ((maxDist / cell).toInt() + 1).coerceAtMost(3)
            var best = -1
            var bestD = maxDist
            for (dy in -reach..reach) for (dx in -reach..reach) {
                val list = map[key(cx + dx, cy + dy)] ?: continue
                for (i in list) {
                    val d = pts[i].dist(p)
                    if (d < bestD) { bestD = d; best = i }
                }
            }
            return best
        }

        fun knn(i: Int, k: Int, maxDist: Float): List<Int> {
            val p = pts[i]
            val reach = (maxDist / cell).toInt() + 1
            val cx = (p.x / cell).toInt()
            val cy = (p.y / cell).toInt()
            val c = ArrayList<Int>()
            for (dy in -reach..reach) for (dx in -reach..reach) {
                map[key(cx + dx, cy + dy)]?.let { l -> for (j in l) if (j != i && pts[j].dist(p) <= maxDist) c += j }
            }
            return c.sortedBy { pts[it].dist(p) }.take(k)
        }
    }

    /**
     * Lattice from corners near where [prior] (image coordinates of the working image) puts
     * the board's interior corners; null if too few agree.
     */
    private fun fitFromPrior(pts: List<Pt>, prior: BoardQuad): Lattice? {
        var toBoard = prior.imageToBoard
        var used = HashMap<Int, IntArray>()
        repeat(2) { round ->
            val next = HashMap<Int, IntArray>()
            val taken = HashSet<Long>()
            for ((k, p) in pts.withIndex()) {
                val b = toBoard.map(p)
                val u = b.x.roundToInt()
                val v = b.y.roundToInt()
                if (u !in 1..7 || v !in 1..7) continue
                if (abs(b.x - u) > 0.22f || abs(b.y - v) > 0.22f) continue
                if (!taken.add((u.toLong() shl 32) or v.toLong())) continue
                next[k] = intArrayOf(u, v)
            }
            if (next.size < 12) return if (round == 0) null else used.takeIf { it.size >= 12 }?.let { lattice(it, pts) }
            used = next
            val h = refit(used, pts) ?: return null
            toBoard = h.inverse()
        }
        return lattice(used, pts)
    }

    private fun lattice(used: Map<Int, IntArray>, pts: List<Pt>): Lattice? {
        val h = refit(used, pts) ?: return null
        val entries = used.entries.toList()
        return Lattice(h, entries.map { it.value }, entries.map { pts[it.key] })
    }

    private fun fitLattice(pts: List<Pt>): Lattice? {
        // Typical spacing: median nearest-neighbour distance.
        val nnAll = pts.indices.map { i -> pts.indices.filter { it != i }.minOf { pts[it].dist(pts[i]) } }.sorted()
        val spacing = nnAll[nnAll.size / 2]
        if (spacing < 4f) return null
        val index = PointIndex(pts, spacing)
        var best: Lattice? = null
        val order = pts.indices.sortedBy { (it * 7919) % pts.size } // deterministic shuffle
        var tries = 0
        var lastGain = 0
        for (i in order) {
            // Stop when more seeds stop finding a bigger lattice.
            if (tries > 160 || (best != null && tries - lastGain > 50)) break
            val nb = index.knn(i, 6, spacing * 2.2f)
            val p = pts[i]
            for (a in nb.indices) for (b in a + 1 until nb.size) {
                val pa = pts[nb[a]]
                val pb = pts[nb[b]]
                val ua = pa - p
                val ub = pb - p
                val la = hypot(ua.x, ua.y)
                val lb = hypot(ub.x, ub.y)
                if (la / lb !in 0.55f..1.8f) continue
                val cosAng = (ua.x * ub.x + ua.y * ub.y) / (la * lb)
                if (abs(cosAng) > 0.65f) continue
                val q = Pt(pa.x + pb.x - p.x, pa.y + pb.y - p.y)
                val qi = index.nearest(q, min(la, lb) * 0.3f)
                if (qi < 0) continue
                tries++
                val seed = Homography.fromPoints(listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(0f, 1f), Pt(1f, 1f)), listOf(p, pa, pb, pts[qi]))
                val grown = grow(seed, pts, index, spacing, listOf(i, nb[a], nb[b], qi), listOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1)))
                if (grown != null && (best == null || grown.coords.size > best.coords.size)) {
                    best = grown
                    lastGain = tries
                }
                if ((best?.coords?.size ?: 0) >= 42) return best
            }
        }
        return best
    }

    private fun grow(seed: Homography, pts: List<Pt>, index: PointIndex, spacing: Float, ids: List<Int>, coords: List<IntArray>): Lattice? {
        var h = seed
        val used = HashMap<Int, IntArray>() // point index -> lattice coord
        val byCoord = HashMap<Long, Int>()
        fun ck(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)
        for (k in ids.indices) {
            used[ids[k]] = coords[k]
            byCoord[ck(coords[k][0], coords[k][1])] = ids[k]
        }
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 12) {
            changed = false
            val frontier = HashSet<Long>()
            val fc = ArrayList<IntArray>()
            for (c in used.values) {
                for ((di, dj) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val ni = c[0] + di
                    val nj = c[1] + dj
                    if (abs(ni) > 14 || abs(nj) > 14) continue
                    val k = ck(ni, nj)
                    if (k in byCoord || !frontier.add(k)) continue
                    fc += intArrayOf(ni, nj)
                }
            }
            for (c in fc) {
                val pred = h.map(c[0].toFloat(), c[1].toFloat())
                val step = h.map(c[0] + 1f, c[1].toFloat()).dist(pred)
                // Degenerate fits predict absurd cell sizes; perspective never changes it 4x.
                if (!(step > 2f) || step > spacing * 4f) continue
                val idx = index.nearest(pred, step * 0.28f)
                if (idx < 0 || idx in used) continue
                used[idx] = c
                byCoord[ck(c[0], c[1])] = idx
                changed = true
            }
            if (changed) h = refit(used, pts) ?: return null
        }
        // Lattice must be a plausible board: at most 7 interior corners per axis (+1 slack).
        val is_ = used.values.map { it[0] }
        val js = used.values.map { it[1] }
        if (is_.max() - is_.min() > 8 || js.max() - js.min() > 8) return null
        val entries = used.entries.toList()
        return Lattice(h, entries.map { it.value }, entries.map { pts[it.key] })
    }

    private fun refit(used: Map<Int, IntArray>, pts: List<Pt>): Homography? {
        if (used.size < 4) return null
        val src = MatOfPoint2f(*used.values.map { Point(it[0].toDouble(), it[1].toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(*used.keys.map { Point(pts[it].x.toDouble(), pts[it].y.toDouble()) }.toTypedArray())
        val m = Calib3d.findHomography(src, dst, 0, 0.0)
        src.release(); dst.release()
        if (m.empty()) return null
        val d = DoubleArray(9)
        m.convertTo(m, CvType.CV_64F)
        m.get(0, 0, d)
        m.release()
        return Homography(d)
    }

    // ---------------------------------------------------------------------------- window

    /**
     * Chooses which 8x8 block of the lattice is the board. Inliers are interior corners, so
     * the window origin o satisfies o < min and o + 8 > max on each axis.
     */
    private fun placeWindow(
        l: Lattice, small: Mat, px: ByteArray, w: Int, h: Int, resp: FloatArray,
        prior: BoardQuad?, expected: IntArray?, allPts: List<Pt> = emptyList(),
    ): Placed? {
        val imin = l.coords.minOf { it[0] }
        val imax = l.coords.maxOf { it[0] }
        val jmin = l.coords.minOf { it[1] }
        val jmax = l.coords.maxOf { it[1] }
        fun range(lo: Int, hi: Int): IntRange = if (hi - 7 <= lo - 1) (hi - 7)..(lo - 1) else (lo - 1)..(hi - 7)
        fun windowQuad(oi: Int, oj: Int): BoardQuad? {
            val corners = listOf(
                l.h.map(oi.toFloat(), oj.toFloat()), l.h.map(oi + 8f, oj.toFloat()),
                l.h.map(oi + 8f, oj + 8f), l.h.map(oi.toFloat(), oj + 8f),
            )
            return BoardQuad.ordered(corners).takeIf { it.isConvex() }
        }
        class Cand(val quad: BoardQuad, val score: Double, val oi: Int, val oj: Int)
        val cands = ArrayList<Cand>()
        for (oi in range(imin, imax)) for (oj in range(jmin, jmax)) {
            val quad = windowQuad(oi, oj) ?: continue
            cands += Cand(quad, windowScore(l.h, oi, oj, px, w, h, resp), oi, oj)
        }
        if (cands.isEmpty()) return null
        val candidates = cands.map { it.quad to it.score }
        if (prior != null) {
            // While tracking, stay on the lattice block nearest to the previous lock...
            val near = cands.minBy { it.quad.alignedTo(prior).maxCornerDistance(prior) }
            val aligned = near.quad.alignedTo(prior)
            if (aligned.maxCornerDistance(prior) < sqrt(prior.area()) * 0.15f) {
                // ...unless a block one square over explains the picture clearly better: tracking
                // that drifted by more than half a square would otherwise lock one square off.
                // Detected X-corners on each block's 49 interior points: a block one square off has
                // a whole row of them on the board's edge, where there are none.
                val nearHits = interiorHits(l.h, near.oi, near.oj, allPts)
                var best = aligned
                var bestScore = near.score
                var bestHits = nearHits
                debugWindows = "near hits=$nearHits n=${l.coords.size}"
                for (di in -1..1) for (dj in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val q = windowQuad(near.oi + di, near.oj + dj) ?: continue
                    val hits = interiorHits(l.h, near.oi + di, near.oj + dj, allPts)
                    debugWindows += " [$di,$dj hits=$hits]"
                    if (hits < bestHits + WINDOW_SWITCH_HITS) continue
                    // Pieces must agree too when the position is known.
                    if (expected != null && occupancyFit(small, q, expected).second < occupancyFit(small, best, expected).second) continue
                    best = q.alignedTo(prior)
                    bestScore = windowScore(l.h, near.oi + di, near.oj + dj, px, w, h, resp)
                    bestHits = hits
                }
                return Placed(best, bestScore, null)
            }
        }
        if (expected != null) {
            var best: Placed? = null
            var bestTotal = Double.NEGATIVE_INFINITY
            for ((quad, ws) in candidates) {
                val (o, fit) = occupancyFit(small, quad, expected)
                val total = ws + fit * 2.0
                if (total > bestTotal) {
                    bestTotal = total
                    best = Placed(quad, ws, if (fit > 0.35) o else null)
                }
            }
            return best
        }
        val best = candidates.maxBy { it.second }
        return Placed(best.first, best.second, null)
    }

    /** Number of the 7x7 interior points of block ([oi], [oj]) with a detected corner nearby. */
    private fun interiorHits(hm: Homography, oi: Int, oj: Int, pts: List<Pt>): Int {
        if (pts.isEmpty()) return 0
        val cell = hm.map(oi + 4f, oj + 4f).dist(hm.map(oi + 5f, oj + 4f))
        val tol = cell * 0.2f
        var hits = 0
        for (a in 1..7) for (b in 1..7) {
            val p = hm.map((oi + a).toFloat(), (oj + b).toFloat())
            if (pts.any { abs(it.x - p.x) < tol && abs(it.y - p.y) < tol }) hits++
        }
        return hits
    }

    /**
     * How well the cell textures/brightness match the expected pieces, for the best of the four
     * orientations: occupied squares are textured, and White's pieces are brighter than Black's.
     */
    private fun occupancyFit(small: Mat, quad: BoardQuad, expected: IntArray): Pair<Int, Double> {
        val warped = CvUtils.warpBoard(small, quad, Cells.SIZE)
        val px = CvUtils.bytes(warped)
        warped.release()
        val (mean, std) = Cells.meanAndStd(px, 0.6f)
        fun z(a: DoubleArray): DoubleArray {
            val mu = a.average()
            val sd = sqrt(a.sumOf { (it - mu) * (it - mu) } / a.size).coerceAtLeast(1e-6)
            return DoubleArray(a.size) { (a[it] - mu) / sd }
        }
        val zs = z(std)
        var bestO = 0
        var best = Double.NEGATIVE_INFINITY
        for (o in 0..3) {
            val occ = DoubleArray(64)
            val col = ArrayList<Pair<Double, Double>>()
            for (row in 0..7) for (c in 0..7) {
                val sq = BoardSpace.squareAtCell(c, row, o)
                val p = expected[sq]
                occ[row * 8 + c] = if (p != 0) 1.0 else 0.0
                if (p != 0) col += mean[row * 8 + c] to (if (com.chesslens.chess.Piece.color(p) == com.chesslens.chess.Piece.WHITE) 1.0 else -1.0)
            }
            val zo = z(occ)
            var occCorr = 0.0
            for (i in 0 until 64) occCorr += zs[i] * zo[i]
            occCorr /= 64
            var colCorr = 0.0
            if (col.size >= 4) {
                val mu = col.sumOf { it.first } / col.size
                val sd = sqrt(col.sumOf { (it.first - mu) * (it.first - mu) } / col.size).coerceAtLeast(1e-6)
                colCorr = col.sumOf { (it.first - mu) / sd * it.second } / col.size
            }
            val score = occCorr + colCorr * 0.5
            if (score > best) { best = score; bestO = o }
        }
        return bestO to best
    }

    /**
     * Interior X-corner evidence + checker alternation inside − continuation/texture outside.
     */
    private fun windowScore(hm: Homography, oi: Int, oj: Int, px: ByteArray, w: Int, h: Int, resp: FloatArray): Double {
        // (1) X-corner response at the 49 interior corners.
        var corner = 0.0
        var seen = 0
        for (a in 1..7) for (b in 1..7) {
            val p = hm.map((oi + a).toFloat(), (oj + b).toFloat())
            val x = p.x.roundToInt()
            val y = p.y.roundToInt()
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) continue
            seen++
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) m = max(m, resp[(y + dy) * w + x + dx])
            corner += min(m, 600f) / 600.0
        }
        // (2) Cell means/texture for the 10x10 block including the ring outside the window.
        val mean = Array(10) { DoubleArray(10) }
        val std = Array(10) { DoubleArray(10) }
        val ok = Array(10) { BooleanArray(10) }
        for (b in -1..8) for (a in -1..8) {
            val c = hm.map(oi + a + 0.5f, oj + b + 0.5f)
            val r = max(1f, hm.map(oi + a + 1f, oj + b + 0.5f).dist(hm.map(oi + a.toFloat(), oj + b + 0.5f)) * 0.22f).toInt()
            val cx = c.x.toInt()
            val cy = c.y.toInt()
            if (cx - r < 0 || cy - r < 0 || cx + r >= w || cy + r >= h) continue
            var s = 0.0
            var s2 = 0.0
            var n = 0
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                val v = (px[y * w + x].toInt() and 0xFF).toDouble()
                s += v; s2 += v * v; n++
            }
            val m = s / n
            mean[b + 1][a + 1] = m
            std[b + 1][a + 1] = sqrt(max(0.0, s2 / n - m * m))
            ok[b + 1][a + 1] = true
        }
        fun m(a: Int, b: Int) = mean[b + 1][a + 1]
        fun valid(a: Int, b: Int) = ok[b + 1][a + 1]
        val blocks = ArrayList<Double>()
        var even = 0.0
        var odd = 0.0
        for (b in 0..7) for (a in 0..7) if (valid(a, b)) { if ((a + b) % 2 == 0) even += m(a, b) else odd += m(a, b) }
        val pol = if (even >= odd) 1.0 else -1.0
        for (b in 0..6) for (a in 0..6) {
            if (!(valid(a, b) && valid(a + 1, b) && valid(a, b + 1) && valid(a + 1, b + 1))) continue
            val d = (m(a, b) + m(a + 1, b + 1) - m(a + 1, b) - m(a, b + 1)) / 2
            blocks += d * (if ((a + b) % 2 == 0) 1.0 else -1.0) * pol
        }
        val checker = if (blocks.isEmpty()) 0.0 else blocks.sorted()[blocks.size / 2] / 80.0
        // (3) Ring: board squares (alternating, or textured by pieces) outside the window are wrong.
        var penalty = 0.0
        for (side in 0..3) {
            var alt = 0.0
            var tex = 0.0
            var n = 0
            for (k in 0..7) {
                val (a, b) = when (side) { 0 -> -1 to k; 1 -> 8 to k; 2 -> k to -1; else -> k to 8 }
                if (!valid(a, b)) continue
                val parity = if (((a + b) % 2 + 2) % 2 == 0) pol else -pol
                // Correlation of the ring with the checker pattern continued outward: real squares
                // score high, a plain border (light or dark) scores ~0.
                alt += parity * m(a, b)
                tex += std[b + 1][a + 1]
                n++
            }
            if (n >= 3) penalty += max(0.0, alt / n) / 40.0 + (tex / n) / 60.0
        }
        return corner / 49.0 * 3.0 + checker - penalty * 0.5 + seen / 49.0 * 0.2
    }

    companion object {
        /** How many more interior corners a neighbouring 8x8 block needs to move the lock by a square. */
        private const val WINDOW_SWITCH_HITS = 4
        private const val RADIUS = 5
        private const val MAX_POINTS = 260
    }
}
