package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface VisionEvent {
    /**
     * One move (or a move and the instant reply, e.g. an online opponent pre-moving) whose
     * square changes match the picture. Moves are legal in sequence from the matched position.
     */
    data class MoveDetected(val moves: List<Int>, val confidence: Float) : VisionEvent {
        val move: Int get() = moves.first()
    }

    /** Something changed on the board but it doesn't match any legal move. */
    data class Unrecognized(val squares: List<Int>) : VisionEvent

    /** Reference image re-captured (after a manual correction / undo). */
    data object Resynced : VisionEvent
}

/** What the camera is looking at; tunes how square changes are measured. */
enum class BoardSurface {
    /** A real board with standing pieces: perspective, occlusion by tall pieces, hands. */
    PHYSICAL,

    /** A board drawn on a monitor, tablet or printout: flat sprites, move highlights, cursor. */
    SCREEN,
}

/** Detector parameters per surface. */
data class DetectorTuning(
    /** Fraction of a cell sampled. */
    val patch: Float,
    /** Shift the sample towards the camera, where piece bases stand (physical boards only). */
    val shiftToCamera: Boolean,
    val stableFrames: Int,
    val motionThreshold: Double,
    val changeThreshold: Double,
    /**
     * Compare cells by structure (zero-mean normalised correlation) instead of brightness:
     * immune to square highlight colours that screens paint on the last move.
     */
    val structural: Boolean,
) {
    companion object {
        fun of(surface: BoardSurface) = when (surface) {
            BoardSurface.PHYSICAL -> DetectorTuning(0.55f, true, 5, 7.0, 13.0, structural = false)
            BoardSurface.SCREEN -> DetectorTuning(0.78f, false, 3, 9.0, 13.0, structural = true)
        }
    }
}

/** Per-cell statistics on a rectified [SIZE]x[SIZE] board image (board space, row-major cells). */
object Cells {
    const val SIZE = 256
    const val CELL = SIZE / 8

    private inline fun forPatch(row: Int, col: Int, patch: Float, dx: Float, dy: Float, f: (Int) -> Unit) {
        val half = (CELL * patch / 2).toInt()
        val cx = (col * CELL + CELL / 2 + dx * CELL).toInt()
        val cy = (row * CELL + CELL / 2 + dy * CELL).toInt()
        for (y in (cy - half).coerceAtLeast(0) until (cy + half).coerceAtMost(SIZE)) {
            val base = y * SIZE
            for (x in (cx - half).coerceAtLeast(0) until (cx + half).coerceAtMost(SIZE)) f(base + x)
        }
    }

    /**
     * Mean absolute difference between [a] and [b] inside each cell. The sampled patch covers
     * [patch] of the cell and is shifted by ([dx], [dy]) cell fractions.
     */
    fun absDiff(a: ByteArray, b: ByteArray, patch: Float, dx: Float = 0f, dy: Float = 0f): DoubleArray {
        val out = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var sum = 0L
            var n = 0
            forPatch(row, col, patch, dx, dy) { i ->
                sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
                n++
            }
            out[row * 8 + col] = if (n == 0) 0.0 else sum.toDouble() / n
        }
        return out
    }

    /**
     * Structural change per cell on a 0..~60 scale comparable to [absDiff]:
     * flat→flat (empty, whatever its colour) = 0, flat↔textured (piece appeared or left) = high,
     * textured→textured = 1 − correlation of the zero-mean patterns (different piece = high,
     * same piece under a highlight = low).
     */
    fun structureChange(a: ByteArray, b: ByteArray, patch: Float, flat: Double = 7.0): DoubleArray {
        val out = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var n = 0
            var sa = 0.0
            var sb = 0.0
            var saa = 0.0
            var sbb = 0.0
            var sab = 0.0
            forPatch(row, col, patch, 0f, 0f) { i ->
                val va = (a[i].toInt() and 0xFF).toDouble()
                val vb = (b[i].toInt() and 0xFF).toDouble()
                sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
                n++
            }
            if (n == 0) continue
            val ma = sa / n
            val mb = sb / n
            val sdA = sqrt(max(0.0, saa / n - ma * ma))
            val sdB = sqrt(max(0.0, sbb / n - mb * mb))
            val flatA = sdA < flat
            val flatB = sdB < flat
            out[row * 8 + col] = when {
                flatA && flatB -> 0.0
                flatA != flatB -> 40.0 + abs(sdA - sdB) * 0.25
                else -> {
                    val zncc = (sab / n - ma * mb) / (sdA * sdB)
                    (1.0 - zncc).coerceIn(0.0, 2.0) * 45.0
                }
            }
        }
        return out
    }

    fun meanAndStd(a: ByteArray, patch: Float): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(64)
        val std = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) {
            var s = 0.0
            var s2 = 0.0
            var n = 0
            forPatch(row, col, patch, 0f, 0f) { i ->
                val v = (a[i].toInt() and 0xFF).toDouble()
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
 * picture of the last known position, and matching the changed squares against legal moves
 * (and, when nothing fits, against a move plus the opponent's instant reply).
 * No piece classifier is needed: the game state comes from the rules.
 */
class MoveDetector(surface: BoardSurface = BoardSurface.PHYSICAL) {
    enum class Resync { NONE, NOW, AFTER_MOTION }

    data class Output(
        val motion: Double,
        val handOverBoard: Boolean,
        val event: VisionEvent? = null,
        /** Per-square change magnitudes (a1 = 0) from the last evaluation, for debugging/FX. */
        val squareChanges: DoubleArray? = null,
    )

    var tuning: DetectorTuning = DetectorTuning.of(surface)
        private set

    private var ref: ByteArray? = null
    private var prev: ByteArray? = null
    private var stableCount = 0
    private var sawMotion = false
    private var resync = Resync.NONE

    /** Switches the measurement model; the reference is re-captured on the next still frame. */
    fun setSurface(surface: BoardSurface) {
        val t = DetectorTuning.of(surface)
        if (t == tuning) return
        tuning = t
        resync = Resync.NOW
    }

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
    val isIdle: Boolean get() = !sawMotion && stableCount >= tuning.stableFrames && resync == Resync.NONE && ref != null

    /**
     * @param warped rectified board ([Cells.SIZE]² bytes)
     * @param board position the board is believed to show (before the next move)
     * @param orientation board-space rotation (see [BoardSpace])
     * @param cameraDir direction towards the camera in board space (cell fractions)
     */
    fun process(warped: ByteArray, board: Board, orientation: Int, cameraDir: Pt = Pt(0f, 0.15f)): Output {
        val t = tuning
        val p = prev
        prev = warped
        if (p == null) {
            if (ref == null) ref = warped
            return Output(0.0, false)
        }
        val motion = Cells.absDiff(warped, p, 0.9f).max()
        if (motion > t.motionThreshold) {
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
        if (stableCount < t.stableFrames) return Output(motion, sawMotion)

        if (resync == Resync.NOW || (resync == Resync.AFTER_MOTION && sawMotion && stableCount == t.stableFrames)) {
            ref = warped
            resync = Resync.NONE
            sawMotion = false
            return Output(motion, false, VisionEvent.Resynced)
        }

        if (stableCount == t.stableFrames && sawMotion) {
            sawMotion = false
            val sq = squareChanges(warped, r, orientation, cameraDir)
            val event = evaluate(sq, board)
            when (event) {
                is VisionEvent.MoveDetected -> ref = warped
                null -> if (sq.count { it > t.changeThreshold } == 0) ref = warped
                else -> Unit
            }
            return Output(motion, false, event, sq)
        }
        // Slowly adapt the reference to lighting while nothing happens.
        if (stableCount % 90 == 0) {
            val drift = squareChanges(warped, r, orientation, cameraDir)
            if (drift.all { it < t.changeThreshold * 0.5 }) ref = warped
        }
        return Output(motion, false)
    }

    /** Per-square (a1 = 0) change between [cur] and [ref] under the current tuning. */
    fun squareChanges(cur: ByteArray, ref: ByteArray, orientation: Int, cameraDir: Pt): DoubleArray {
        val t = tuning
        val cells = if (t.structural) {
            Cells.structureChange(cur, ref, t.patch)
        } else {
            val shift = if (t.shiftToCamera) cameraDir else Pt(0f, 0f)
            val raw = Cells.absDiff(cur, ref, t.patch, shift.x, shift.y)
            // Compensate global exposure changes.
            val median = raw.sorted()[32]
            DoubleArray(64) { max(0.0, raw[it] - median) }
        }
        val sq = DoubleArray(64)
        for (row in 0..7) for (col in 0..7) sq[BoardSpace.squareAtCell(col, row, orientation)] = cells[row * 8 + col]
        return sq
    }

    private class Cand(val moves: List<Int>, val score: Double, val minIn: Double, val finalKey: Long)

    /** Picks the move (or move + reply) that best explains per-square changes [c]. */
    fun evaluate(c: DoubleArray, board: Board): VisionEvent? {
        val threshold = tuning.changeThreshold
        val changed = (0 until 64).filter { c[it] > threshold }
        if (changed.isEmpty()) return null
        if (changed.size > 12) return null // camera moved or hand still in view

        fun score(expected: List<Int>): Pair<Double, Double> {
            if (expected.isEmpty()) return Double.NEGATIVE_INFINITY to 0.0
            var minIn = Double.MAX_VALUE
            var sumIn = 0.0
            for (s in expected) {
                minIn = minOf(minIn, c[s])
                sumIn += c[s]
            }
            var maxOut = 0.0
            for (s in changed) if (s !in expected) maxOut = max(maxOut, c[s])
            return (minIn * 0.6 + sumIn / expected.size * 0.4 - maxOut * 0.7) to minIn
        }

        fun pick(cands: List<Cand>): Cand? {
            // Candidates reaching the same position are the same answer (e.g. promotion pieces).
            val best = cands.groupBy { it.finalKey }.map { (_, g) -> g.maxBy { it.score } }.sortedByDescending { it.score }
            val top = best.firstOrNull() ?: return null
            val second = best.getOrNull(1)?.score ?: Double.NEGATIVE_INFINITY
            val ok = top.minIn > threshold * 0.6 && top.score > 0 && top.score - second > threshold * 0.25
            return if (ok) top else null
        }

        val before = board.squares.copyOf()
        val singles = ArrayList<Cand>()
        val work = board.copy()
        val legal = work.legalMoves()
        for (m in legal) {
            work.makeMove(m)
            val (s, minIn) = score(diff(before, work.squares))
            singles += Cand(listOf(m), preferQueen(m, s), minIn, work.key)
            work.unmakeMove()
        }
        pick(singles)?.let { return VisionEvent.MoveDetected(it.moves, confidence(it, singles)) }

        // A move and an immediate reply (online opponents, pre-moves, or a hand that left late).
        if (changed.size >= 3) {
            val pairs = ArrayList<Cand>()
            for (m1 in legal) {
                work.makeMove(m1)
                for (m2 in work.legalMoves()) {
                    work.makeMove(m2)
                    val (s, minIn) = score(diff(before, work.squares))
                    pairs += Cand(listOf(m1, m2), preferQueen(m1, preferQueen(m2, s)), minIn, work.key)
                    work.unmakeMove()
                }
                work.unmakeMove()
            }
            pick(pairs)?.let { return VisionEvent.MoveDetected(it.moves, confidence(it, pairs)) }
        }
        return if (changed.size <= 6) VisionEvent.Unrecognized(changed) else null
    }

    private fun preferQueen(m: Int, s: Double): Double {
        val promo = com.chesslens.chess.Move.promo(m)
        return if (promo != 0 && promo != Piece.QUEEN) s - 1e-3 else s
    }

    private fun confidence(top: Cand, all: List<Cand>): Float {
        val second = all.filter { it.finalKey != top.finalKey }.maxOfOrNull { it.score } ?: 0.0
        return ((top.score - max(0.0, second)) / (top.score + 1.0)).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** Squares whose contents differ between two piece arrays. */
        fun diff(a: IntArray, b: IntArray): List<Int> {
            val out = ArrayList<Int>(4)
            for (i in 0 until 64) if (a[i] != b[i]) out += i
            return out
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
