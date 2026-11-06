package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Move
import com.chesslens.chess.Piece
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface VisionEvent {
    /** A legal move whose square changes match the camera picture. */
    data class MoveDetected(val move: Int, val confidence: Float) : VisionEvent

    /** Something changed on the board but it doesn't match any legal move. */
    data class Unrecognized(val squares: List<Int>) : VisionEvent

    /** Reference image re-captured (after a manual correction / undo). */
    data object Resynced : VisionEvent
}

/** Per-cell statistics on a rectified [SIZE]x[SIZE] board image (board space, row-major cells). */
object Cells {
    const val SIZE = 256
    const val CELL = SIZE / 8

    /**
     * Mean absolute difference between [a] and [b] inside each cell. The sampled patch covers
     * [patch] of the cell and is shifted by ([dx], [dy]) cell fractions (towards the camera, where
     * piece bases are).
     */
    fun absDiff(a: ByteArray, b: ByteArray, patch: Float, dx: Float = 0f, dy: Float = 0f): DoubleArray {
        val out = DoubleArray(64)
        val half = (CELL * patch / 2).toInt()
        for (row in 0..7) for (col in 0..7) {
            val cx = (col * CELL + CELL / 2 + dx * CELL).toInt()
            val cy = (row * CELL + CELL / 2 + dy * CELL).toInt()
            var sum = 0L
            var n = 0
            for (y in (cy - half).coerceAtLeast(0) until (cy + half).coerceAtMost(SIZE)) {
                val base = y * SIZE
                for (x in (cx - half).coerceAtLeast(0) until (cx + half).coerceAtMost(SIZE)) {
                    sum += abs((a[base + x].toInt() and 0xFF) - (b[base + x].toInt() and 0xFF))
                    n++
                }
            }
            out[row * 8 + col] = if (n == 0) 0.0 else sum.toDouble() / n
        }
        return out
    }

    fun meanAndStd(a: ByteArray, patch: Float): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(64)
        val std = DoubleArray(64)
        val half = (CELL * patch / 2).toInt()
        for (row in 0..7) for (col in 0..7) {
            val cx = col * CELL + CELL / 2
            val cy = row * CELL + CELL / 2
            var s = 0.0
            var s2 = 0.0
            var n = 0
            for (y in cy - half until cy + half) for (x in cx - half until cx + half) {
                val v = (a[y * SIZE + x].toInt() and 0xFF).toDouble()
                s += v; s2 += v * v; n++
            }
            val m = s / n
            mean[row * 8 + col] = m
            std[row * 8 + col] = sqrt(max(0.0, s2 / n - m * m))
        }
        return mean to std
    }
}

/**
 * Detects moves by comparing the rectified board after the scene settles with a reference
 * picture of the last known position, and matching the changed squares against legal moves.
 * No piece classifier is needed: the game state comes from the rules.
 */
class MoveDetector(
    private val motionThreshold: Double = 7.0,
    private val stableFramesNeeded: Int = 5,
    private val changeThreshold: Double = 13.0,
) {
    enum class Resync { NONE, NOW, AFTER_MOTION }

    data class Output(
        val motion: Double,
        val handOverBoard: Boolean,
        val event: VisionEvent? = null,
        /** Per-square change magnitudes (a1 = 0) from the last evaluation, for debugging/FX. */
        val squareChanges: DoubleArray? = null,
    )

    private var ref: ByteArray? = null
    private var prev: ByteArray? = null
    private var stableCount = 0
    private var sawMotion = false
    private var resync = Resync.NONE

    fun reset() {
        ref = null
        prev = null
        stableCount = 0
        sawMotion = false
        resync = Resync.NONE
    }

    fun requestResync(mode: Resync) {
        resync = mode
    }

    /** Replaces the reference (and previous frame) with [warped] right away. */
    fun setReference(warped: ByteArray) {
        ref = warped
        prev = warped
    }

    /** True when the scene has been still since the last reference capture. */
    val isIdle: Boolean get() = !sawMotion && stableCount >= stableFramesNeeded && resync == Resync.NONE && ref != null

    /**
     * @param warped rectified board ([Cells.SIZE]² bytes)
     * @param board position the physical board is believed to show (before the next move)
     * @param orientation board-space rotation (see [BoardSpace])
     * @param cameraDir unit direction towards the camera in board space (cell fractions)
     */
    fun process(warped: ByteArray, board: Board, orientation: Int, cameraDir: Pt = Pt(0f, 0.15f)): Output {
        val p = prev
        prev = warped
        if (p == null) {
            if (ref == null) ref = warped
            return Output(0.0, false)
        }
        val motionCells = Cells.absDiff(warped, p, 0.9f)
        val motion = motionCells.max()
        if (motion > motionThreshold) {
            stableCount = 0
            sawMotion = true
            return Output(motion, true)
        }
        stableCount++
        val r = ref
        if (r == null) {
            ref = warped
            return Output(motion, false)
        }
        if (stableCount < stableFramesNeeded) return Output(motion, sawMotion)

        if (resync == Resync.NOW || (resync == Resync.AFTER_MOTION && sawMotion && stableCount == stableFramesNeeded)) {
            ref = warped
            resync = Resync.NONE
            sawMotion = false
            return Output(motion, false, VisionEvent.Resynced)
        }

        if (stableCount == stableFramesNeeded && sawMotion) {
            sawMotion = false
            val cellChange = Cells.absDiff(warped, r, 0.55f, cameraDir.x, cameraDir.y)
            // Compensate global exposure changes.
            val median = cellChange.sorted()[32]
            val sq = DoubleArray(64)
            for (row in 0..7) for (col in 0..7) {
                sq[BoardSpace.squareAtCell(col, row, orientation)] = max(0.0, cellChange[row * 8 + col] - median)
            }
            val event = evaluate(sq, board)
            when (event) {
                is VisionEvent.MoveDetected -> ref = warped
                null -> if (sq.count { it > changeThreshold } == 0) ref = warped
                else -> Unit
            }
            return Output(motion, false, event, sq)
        }
        // Slowly adapt the reference to lighting while nothing happens.
        if (stableCount % 90 == 0) {
            val drift = Cells.absDiff(warped, r, 0.55f)
            val median = drift.sorted()[32]
            if (drift.all { it - median < changeThreshold * 0.5 }) ref = warped
        }
        return Output(motion, false)
    }

    /** Picks the legal move that best explains per-square changes [c]. */
    fun evaluate(c: DoubleArray, board: Board): VisionEvent? {
        val changed = (0 until 64).filter { c[it] > changeThreshold }
        if (changed.isEmpty()) return null
        if (changed.size > 10) return null // camera moved or hand still in view

        class Cand(val move: Int, val score: Double, val minIn: Double)
        val byPair = HashMap<Int, Cand>()
        for (m in board.legalMoves()) {
            val touched = touchedSquares(board, m)
            var minIn = Double.MAX_VALUE
            var sumIn = 0.0
            for (s in touched) {
                minIn = minOf(minIn, c[s])
                sumIn += c[s]
            }
            var maxOut = 0.0
            for (s in changed) if (s !in touched) maxOut = max(maxOut, c[s])
            val score = minIn * 0.6 + sumIn / touched.size * 0.4 - maxOut * 0.7
            val key = Move.from(m) * 64 + Move.to(m)
            val prevCand = byPair[key]
            // Promotions share squares: keep the queen promotion.
            if (prevCand == null || (Move.promo(m) == Piece.QUEEN && score >= prevCand.score)) {
                byPair[key] = Cand(m, score, minIn)
            }
        }
        val ranked = byPair.values.sortedByDescending { it.score }
        val best = ranked.firstOrNull()
        val secondScore = ranked.getOrNull(1)?.score ?: Double.NEGATIVE_INFINITY
        if (best != null && best.minIn > changeThreshold * 0.6 && best.score > 0 &&
            best.score - secondScore > changeThreshold * 0.25
        ) {
            val confidence = ((best.score - max(0.0, secondScore)) / (best.score + 1.0)).toFloat().coerceIn(0f, 1f)
            return VisionEvent.MoveDetected(best.move, confidence)
        }
        return if (changed.size <= 6) VisionEvent.Unrecognized(changed) else null
    }

    companion object {
        fun touchedSquares(board: Board, m: Int): List<Int> {
            val from = Move.from(m)
            val to = Move.to(m)
            return when {
                Move.isCastle(m) -> when (to) {
                    6 -> listOf(4, 5, 6, 7)
                    2 -> listOf(4, 3, 2, 0)
                    62 -> listOf(60, 61, 62, 63)
                    else -> listOf(60, 59, 58, 56)
                }
                Move.isEnPassant(m) -> listOf(from, to, if (board.side == Piece.WHITE) to - 8 else to + 8)
                else -> listOf(from, to)
            }
        }
    }
}

/** Guesses which side of the rectified board White sits on, assuming the start position. */
object OrientationEstimator {
    /** Returns orientation 0..3 or null when the picture doesn't look like a start position. */
    fun estimate(warped: ByteArray): Int? {
        val (mean, std) = Cells.meanAndStd(warped, 0.7f)
        val rowE = DoubleArray(8) { r -> (0..7).sumOf { std[r * 8 + it] } / 8 }
        val colE = DoubleArray(8) { c -> (0..7).sumOf { std[it * 8 + c] } / 8 }
        fun axisScore(e: DoubleArray) = (e[0] + e[1] + e[6] + e[7]) / 4 - (e[2] + e[3] + e[4] + e[5]) / 4
        val rowsScore = axisScore(rowE)
        val colsScore = axisScore(colE)
        if (max(rowsScore, colsScore) < 2.0) return null
        return if (rowsScore >= colsScore) {
            val top = (0..1).sumOf { r -> (0..7).sumOf { mean[r * 8 + it] } }
            val bottom = (6..7).sumOf { r -> (0..7).sumOf { mean[r * 8 + it] } }
            if (bottom >= top) 0 else 2
        } else {
            val left = (0..7).sumOf { r -> mean[r * 8] + mean[r * 8 + 1] }
            val right = (0..7).sumOf { r -> mean[r * 8 + 6] + mean[r * 8 + 7] }
            if (left >= right) 1 else 3
        }
    }
}
