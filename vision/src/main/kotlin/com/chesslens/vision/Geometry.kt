package com.chesslens.vision

import kotlin.math.abs
import kotlin.math.hypot

/** Plain 2D point (image or board space). */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun dist(o: Pt) = hypot((x - o.x).toDouble(), (y - o.y).toDouble()).toFloat()
    fun lerp(o: Pt, t: Float) = Pt(x + (o.x - x) * t, y + (o.y - y) * t)
}

/** 3x3 projective transform stored row-major. */
class Homography(val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    fun map(x: Float, y: Float): Pt {
        val w = m[6] * x + m[7] * y + m[8]
        val iw = if (abs(w) < 1e-12) 1e12 else 1.0 / w
        return Pt(((m[0] * x + m[1] * y + m[2]) * iw).toFloat(), ((m[3] * x + m[4] * y + m[5]) * iw).toFloat())
    }

    fun map(p: Pt) = map(p.x, p.y)

    /** Projective "depth" term; > 0 for points in front of the camera for a sane board quad. */
    fun w(x: Float, y: Float): Double = m[6] * x + m[7] * y + m[8]

    fun then(o: Homography): Homography {
        // result = o * this
        val a = o.m
        val b = m
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return Homography(r)
    }

    fun inverse(): Homography {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        val id = if (abs(det) < 1e-15) 0.0 else 1.0 / det
        return Homography(
            doubleArrayOf(
                (a[4] * a[8] - a[5] * a[7]) * id, (a[2] * a[7] - a[1] * a[8]) * id, (a[1] * a[5] - a[2] * a[4]) * id,
                (a[5] * a[6] - a[3] * a[8]) * id, (a[0] * a[8] - a[2] * a[6]) * id, (a[2] * a[3] - a[0] * a[5]) * id,
                (a[3] * a[7] - a[4] * a[6]) * id, (a[1] * a[6] - a[0] * a[7]) * id, (a[0] * a[4] - a[1] * a[3]) * id,
            ),
        )
    }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun scale(sx: Float, sy: Float, tx: Float = 0f, ty: Float = 0f) =
            Homography(doubleArrayOf(sx.toDouble(), 0.0, tx.toDouble(), 0.0, sy.toDouble(), ty.toDouble(), 0.0, 0.0, 1.0))

        /** Solves the homography mapping 4 [src] points onto 4 [dst] points (DLT, h33 = 1). */
        fun fromPoints(src: List<Pt>, dst: List<Pt>): Homography {
            require(src.size == 4 && dst.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val x = src[i].x.toDouble()
                val y = src[i].y.toDouble()
                val u = dst[i].x.toDouble()
                val v = dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting on the 8x8 system.
            for (c in 0 until 8) {
                var p = c
                for (r in c + 1 until 8) if (abs(a[r][c]) > abs(a[p][c])) p = r
                val t = a[c]; a[c] = a[p]; a[p] = t
                val piv = a[c][c]
                if (abs(piv) < 1e-12) return IDENTITY
                for (k in c until 9) a[c][k] /= piv
                for (r in 0 until 8) {
                    if (r == c) continue
                    val f = a[r][c]
                    if (f == 0.0) continue
                    for (k in c until 9) a[r][k] -= f * a[c][k]
                }
            }
            return Homography(DoubleArray(9) { if (it < 8) a[it][8] else 1.0 })
        }
    }
}

/**
 * Four board corners in image coordinates, ordered clockwise as seen in the image starting from
 * the top-left one. Board space is the unit-free square [0,8]x[0,8] whose (0,0) is corners[0],
 * (8,0) corners[1], (8,8) corners[2] and (0,8) corners[3].
 */
data class BoardQuad(val corners: List<Pt>) {
    init {
        require(corners.size == 4)
    }

    /** Board space (0..8) -> image coordinates. */
    val boardToImage: Homography by lazy { Homography.fromPoints(BOARD_CORNERS, corners) }
    val imageToBoard: Homography by lazy { boardToImage.inverse() }

    fun area(): Float {
        var s = 0f
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun isConvex(): Boolean {
        var sign = 0
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun maxCornerDistance(o: BoardQuad): Float = (0..3).maxOf { corners[it].dist(o.corners[it]) }

    /**
     * The same quad with its corners cyclically relabelled to best match [other]'s labelling
     * (keeps board space, and therefore orientation, stable while the camera rotates).
     */
    fun alignedTo(other: BoardQuad): BoardQuad {
        val s = (0..3).minBy { k -> (0..3).maxOf { corners[(it + k) % 4].dist(other.corners[it]) } }
        return BoardQuad((0..3).map { corners[(it + s) % 4] })
    }

    fun map(f: (Pt) -> Pt) = BoardQuad(corners.map(f))

    fun lerp(o: BoardQuad, t: Float) = BoardQuad((0..3).map { corners[it].lerp(o.corners[it], t) })

    /** Sub-quad of board space [u0,u1]x[v0,v1] (fractions 0..1) mapped back to the image. */
    fun inset(left: Float, top: Float, right: Float, bottom: Float): BoardQuad {
        val h = boardToImage
        return BoardQuad(
            listOf(
                h.map(8f * left, 8f * top),
                h.map(8f * (1 - right), 8f * top),
                h.map(8f * (1 - right), 8f * (1 - bottom)),
                h.map(8f * left, 8f * (1 - bottom)),
            ),
        )
    }

    companion object {
        val BOARD_CORNERS = listOf(Pt(0f, 0f), Pt(8f, 0f), Pt(8f, 8f), Pt(0f, 8f))

        /** Orders 4 arbitrary points clockwise (image y-down) starting at the top-left. */
        fun ordered(points: List<Pt>): BoardQuad {
            require(points.size == 4)
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            val sorted = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) } // clockwise in y-down
            // Start from the point with the smallest x + y (top-left).
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            return BoardQuad((0..3).map { sorted[(start + it) % 4] })
        }

        fun centered(width: Float, height: Float, fraction: Float = 0.7f): BoardQuad {
            val s = minOf(width, height) * fraction
            val x0 = (width - s) / 2
            val y0 = (height - s) / 2
            return BoardQuad(listOf(Pt(x0, y0), Pt(x0 + s, y0), Pt(x0 + s, y0 + s), Pt(x0, y0 + s)))
        }
    }
}

/**
 * Maps chess coordinates to board space. Chess space (cx, cy) has cx = 0..8 from the a-file to
 * the h-file and cy = 0..8 from rank 1 to rank 8. [orientation] k rotates by k*90° clockwise:
 * k = 0 means White sits along the bottom edge (corners[3]..corners[2]).
 */
object BoardSpace {
    fun fromChess(cx: Float, cy: Float, orientation: Int): Pt {
        val u0 = cx
        val v0 = 8f - cy
        return when (orientation and 3) {
            0 -> Pt(u0, v0)
            1 -> Pt(8f - v0, u0)
            2 -> Pt(8f - u0, 8f - v0)
            else -> Pt(v0, 8f - u0)
        }
    }

    fun toChess(u: Float, v: Float, orientation: Int): Pt {
        val (u0, v0) = when (orientation and 3) {
            0 -> u to v
            1 -> v to 8f - u
            2 -> 8f - u to 8f - v
            else -> 8f - v to u
        }
        return Pt(u0, 8f - v0)
    }

    fun squareCenter(sq: Int, orientation: Int): Pt =
        fromChess((sq and 7) + 0.5f, (sq shr 3) + 0.5f, orientation)

    /** Square index (a1 = 0) under board-space cell (col, row), both 0..7. */
    fun squareAtCell(col: Int, row: Int, orientation: Int): Int {
        val c = toChess(col + 0.5f, row + 0.5f, orientation)
        val f = c.x.toInt().coerceIn(0, 7)
        val r = c.y.toInt().coerceIn(0, 7)
        return r * 8 + f
    }
}
