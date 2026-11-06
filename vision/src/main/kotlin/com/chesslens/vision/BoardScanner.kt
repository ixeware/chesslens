package com.chesslens.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the camera can tell about a position without a piece classifier: which squares hold a
 * piece and whether it is light or dark, as evidence (log-odds-like scores) per board-space
 * cell (row-major, row 0 = top of the rectified board).
 */
class BoardScan(
    /** > 0: a piece stands here; < 0: empty; 0: can't tell (e.g. out of frame). */
    val occupancyCells: DoubleArray,
    /** > 0: light (White) piece; < 0: dark piece. Only meaningful where occupancy > 0. */
    val colorCells: DoubleArray,
    /** ~1 looking straight down, smaller for a low, oblique view. */
    val viewSteepness: Float,
    /** Orientation the board was tracked with when scanned. */
    val orientation: Int,
    val note: String = "",
) {
    /** Evidence by chess square (a1 = 0) if White sits as [orientation] says. */
    fun occupancy(orientation: Int = this.orientation): DoubleArray = bySquare(occupancyCells, orientation)
    fun color(orientation: Int = this.orientation): DoubleArray = bySquare(colorCells, orientation)

    private fun bySquare(cells: DoubleArray, orientation: Int): DoubleArray {
        val out = DoubleArray(64)
        for (c in 0 until 64) out[BoardSpace.squareAtCell(c % 8, c / 8, orientation)] = cells[c]
        return out
    }

    fun occupied(sq: Int): Boolean = occupancy()[sq] > 0
    fun white(sq: Int): Boolean = color()[sq] > 0
    val pieceCount: Int get() = occupancyCells.count { it > 0 }

    companion object {
        /** Scans from a view flatter than this are too ambiguous to trust (tall pieces hide squares). */
        const val MIN_STEEPNESS = 0.76f
    }
}

/**
 * Reads occupancy and piece colours off the rectified board (averaged over a few still frames).
 *
 * Standing pieces lean away from the camera in the rectified picture: a piece covers the half of
 * its own square nearest the camera with its base, and its body spills into the square behind.
 * Squares are therefore decided from the camera outwards: the near half shows a base unless the
 * square in front holds a piece, in which case only the far half is informative (and less so).
 */
class BoardScanner {

    /**
     * @param px rectified board, [Cells.SIZE]² bytes, board space rows/cols
     * @param camDir direction towards the camera in board space (zero for flat screens)
     * @param visibleCells cells fully inside the frame (row-major); others are reported as unknown
     */
    fun scan(px: ByteArray, orientation: Int, camDir: Pt, visibleCells: BooleanArray?, steepness: Float = 1f): BoardScan {
        val n = Cells.CELL
        val flat = hypot(camDir.x, camDir.y) < 1e-3f
        // Step from a cell towards the camera, along the dominant axis.
        val alongY = abs(camDir.y) >= abs(camDir.x)
        val toward = if (flat) 0 else if (alongY) (if (camDir.y > 0) 1 else -1) else (if (camDir.x > 0) 1 else -1)

        val near = Array(64) { patch(px, it % 8, it / 8, n, if (flat) Region.ALL else Region.NEAR, alongY, toward) }
        val far = Array(64) { patch(px, it % 8, it / 8, n, if (flat) Region.ALL else Region.FAR, alongY, toward) }

        // Lighting varies across a board: fit light/dark square levels as planes over the board.
        val par = IntArray(64) { ((it % 8) + (it / 8)) % 2 }
        val med0 = (0 until 64).filter { par[it] == 0 }.map { near[it].median }.sorted()
        val med1 = (0 until 64).filter { par[it] == 1 }.map { near[it].median }.sorted()
        val lightParity = if (med0[med0.size / 2] >= med1[med1.size / 2]) 0 else 1
        fun flattest(light: Boolean) = (0 until 64).filter { (par[it] == lightParity) == light }.sortedBy { near[it].std }.let { it.take(max(4, it.size / 3)) }
        val lightCells = flattest(true)
        val darkCells = flattest(false)
        val lightPlane = Plane.fit(lightCells.map { Triple(it % 8 + 0.5, it / 8 + 0.5, near[it].median) })
        val darkPlane = Plane.fit(darkCells.map { Triple(it % 8 + 0.5, it / 8 + 0.5, near[it].median) })
        val noise = (lightCells + darkCells).map { near[it].std }.sorted().let { it[it.size / 2] }.coerceIn(0.8, 6.0)

        val occCell = DoubleArray(64)
        val colCell = DoubleArray(64)
        val thrNear = noise * 2.5 + 3.5
        val thrFar = noise * 2.5 + 7.0
        val thrBand = noise * 2.5 + 4.0
        val farCap = ((steepness - 0.55) * 6.0).coerceIn(0.6, 2.5)
        // From the camera outwards, so each cell knows whether the one in front holds a piece.
        val order = (0 until 64).sortedBy { c ->
            val a = if (alongY) c / 8 else c % 8
            if (toward > 0) -a else a
        }
        for (c in order) {
            val col = c % 8
            val row = c / 8
            val x = col + 0.5
            val y = row + 0.5
            val lo = darkPlane.at(x, y)
            val hi = lightPlane.at(x, y)
            val span = (hi - lo).coerceAtLeast(25.0)
            val light = par[c] == lightParity
            // The square in front throws its piece over this one when that piece crosses the
            // edge between them: then the band along that edge is covered, not bare board.
            val front = when {
                flat -> -1
                alongY -> if ((row + toward) in 0..7) (row + toward) * 8 + col else -1
                else -> if ((col + toward) in 0..7) row * 8 + col + toward else -1
            }
            // Covered = not bare board of the expected colour, or textured.
            fun covered(band: Patch, lightSq: Boolean, l: Double, sp: Double): Boolean {
                val bgT = if (lightSq) 1.0 else 0.0
                val off = band.values.count { abs((it - l) / sp - bgT) > 0.3 }
                return off > band.values.size * 0.35 || band.std > thrBand
            }
            // A piece in front crosses the shared edge: it covers both sides of it (this cell's
            // own base never reaches into the square in front).
            val shadowed = front >= 0 && (occCell[front] > 0.5 || run {
                val fx = (front % 8) + 0.5
                val fy = (front / 8) + 0.5
                val fl = darkPlane.at(fx, fy)
                val fs = (lightPlane.at(fx, fy) - fl).coerceAtLeast(25.0)
                covered(patch(px, col, row, n, Region.BAND, alongY, toward), light, lo, span) &&
                    covered(patch(px, front % 8, front / 8, n, Region.FAR_BAND, alongY, toward), par[front] == lightParity, fl, fs)
            })
            val use = if (shadowed) far[c] else near[c]
            val e = if (shadowed) ((use.std - thrFar) / 4.0).coerceIn(-farCap, farCap)
            else ((use.std - thrNear) / 2.5).coerceIn(-4.0, 4.0)
            occCell[c] = e

            // Colour: pixels on the scale dark-square level (0) … light-square level (1).
            var dark = 0
            var bright = 0
            for (v in use.values) {
                val t = (v - lo) / span
                if (t < 0.35) dark++
                if (t > 0.65) bright++
            }
            val tot = use.values.size.toDouble()
            colCell[c] = if (light) (0.2 - dark / tot) / 0.07 else (bright / tot - 0.2) / 0.07
            colCell[c] = colCell[c].coerceIn(-4.0, 4.0) * if (shadowed) 0.6 else 1.0
        }

        if (visibleCells != null) for (c in 0 until 64) if (!visibleCells[c]) { occCell[c] = 0.0; colCell[c] = 0.0 }
        val note = "noise=%.1f light=%.0f dark=%.0f steep=%.2f".format(noise, lightPlane.at(4.0, 4.0), darkPlane.at(4.0, 4.0), steepness)
        return BoardScan(occCell, colCell, steepness, orientation, note)
    }

    private enum class Region { NEAR, FAR, ALL, BAND, FAR_BAND }

    private class Patch(val std: Double, val median: Double, val values: IntArray)

    /** Pixel statistics of part of a cell; [toward] is the step towards the camera. */
    private fun patch(px: ByteArray, col: Int, row: Int, n: Int, region: Region, alongY: Boolean, toward: Int): Patch {
        // Along the camera axis, in cell fractions from the centre (+ = towards the camera).
        val (a0, a1) = when (region) {
            Region.NEAR -> 0.0 to 0.40
            Region.FAR -> -0.40 to -0.05
            Region.ALL -> -0.36 to 0.36
            Region.BAND -> 0.38 to 0.47
            Region.FAR_BAND -> -0.47 to -0.38
        }
        val across = if (region == Region.BAND || region == Region.FAR_BAND) 0.22 else 0.32
        val cx = col * n + n / 2.0
        val cy = row * n + n / 2.0
        val s = if (toward == 0) 1 else toward
        val (ya, yb, xa, xb) = if (alongY || toward == 0) {
            val p = cy + s * a0 * n
            val q = cy + s * a1 * n
            listOf(minOf(p, q), max(p, q), cx - across * n, cx + across * n)
        } else {
            val p = cx + s * a0 * n
            val q = cx + s * a1 * n
            listOf(cy - across * n, cy + across * n, minOf(p, q), max(p, q))
        }
        val y0 = ya.toInt().coerceIn(0, Cells.SIZE - 1)
        val y1 = yb.toInt().coerceIn(y0 + 1, Cells.SIZE)
        val x0 = xa.toInt().coerceIn(0, Cells.SIZE - 1)
        val x1 = xb.toInt().coerceIn(x0 + 1, Cells.SIZE)
        val vals = IntArray((y1 - y0) * (x1 - x0))
        var k = 0
        var sum = 0.0
        var sum2 = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val v = px[y * Cells.SIZE + x].toInt() and 0xFF
            vals[k++] = v
            sum += v; sum2 += v.toDouble() * v
        }
        val m = sum / k
        val sorted = vals.sortedArray()
        return Patch(sqrt(max(0.0, sum2 / k - m * m)), sorted[sorted.size / 2].toDouble(), vals)
    }

    /** z = a + b·x + c·y, least squares (falls back to the mean). */
    private class Plane(val a: Double, val b: Double, val c: Double) {
        fun at(x: Double, y: Double) = a + b * x + c * y

        companion object {
            fun fit(p: List<Triple<Double, Double, Double>>): Plane {
                val mean = p.sumOf { it.third } / p.size
                if (p.size < 4) return Plane(mean, 0.0, 0.0)
                val mx = p.sumOf { it.first } / p.size
                val my = p.sumOf { it.second } / p.size
                var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxz = 0.0; var syz = 0.0
                for ((x, y, z) in p) {
                    val dx = x - mx; val dy = y - my; val dz = z - mean
                    sxx += dx * dx; sxy += dx * dy; syy += dy * dy; sxz += dx * dz; syz += dy * dz
                }
                val det = sxx * syy - sxy * sxy
                if (abs(det) < 1e-6) return Plane(mean, 0.0, 0.0)
                val b = (sxz * syy - syz * sxy) / det
                val c = (syz * sxx - sxz * sxy) / det
                return Plane(mean - b * mx - c * my, b, c)
            }
        }
    }

    companion object {
        /** Unit direction towards the camera in board space (cell units). */
        fun cameraDir(q: BoardQuad, imageHeight: Int): Pt {
            val inv = q.imageToBoard
            val cx = q.corners.sumOf { it.x.toDouble() }.toFloat() / 4
            val cy = q.corners.sumOf { it.y.toDouble() }.toFloat() / 4
            val a = inv.map(cx, cy)
            val b = inv.map(cx, cy + imageHeight * 0.05f)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-4f) return Pt(0f, 0f)
            return Pt(dx / len, dy / len)
        }

        /**
         * How steeply the camera looks down at the board centre: image length of a step towards
         * the camera over that of an equal step across (≈ sine of the elevation).
         */
        fun steepness(q: BoardQuad, camDir: Pt): Float {
            val h = q.boardToImage
            val c = h.map(4f, 4f)
            val d = h.map(4f + camDir.x * 0.5f, 4f + camDir.y * 0.5f)
            val p = h.map(4f - camDir.y * 0.5f, 4f + camDir.x * 0.5f)
            val ld = hypot(d.x - c.x, d.y - c.y)
            val lp = hypot(p.x - c.x, p.y - c.y)
            if (lp < 1e-3f) return 1f
            return (ld / lp).coerceIn(0f, 1.5f)
        }
    }
}

/** Turns a [BoardScan] into a position, also working out which side White sits on. */
object ScanInterpreter {
    class Result(val guess: com.chesslens.chess.PositionGuesser.Guess, val orientation: Int)

    /**
     * @param known positions the board probably shows (game history), tried as they are
     * @param current game position whose near successors are tried too
     */
    fun interpret(scan: BoardScan, known: List<com.chesslens.chess.Board>, current: com.chesslens.chess.Board?): Result {
        var best: Result? = null
        var bestCost = Double.MAX_VALUE
        for (o in 0..3) {
            val e = com.chesslens.chess.PositionGuesser.Evidence(scan.occupancy(o), scan.color(o))
            val g = com.chesslens.chess.PositionGuesser.guess(e, known, current, depth = if (o == scan.orientation) 2 else 1)
            // Keep the tracked orientation unless another explains the picture clearly better.
            val cost = g.cost + if (o == scan.orientation) 0.0 else 1.0
            if (cost < bestCost) {
                bestCost = cost
                best = Result(g, o)
            }
        }
        return best!!
    }
}
