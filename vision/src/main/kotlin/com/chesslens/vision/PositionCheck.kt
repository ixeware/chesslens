package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.PositionGuesser

/**
 * Compares what the board shows (a [BoardScan]) with the position the app believes, about once a
 * second while nothing happens. Finds moves the move detector missed (the board is a move or two
 * ahead), a move it got wrong (the board matches the position before it, or a different move),
 * and otherwise reports that the two disagree so the user can fix it.
 */
class PositionCheck {
    sealed interface Verdict {
        data object Ok : Verdict
        /** Undo [undo] plies, then play [moves], to match the board. */
        data class Explained(val undo: Int, val moves: List<Int>) : Verdict
        /** Board and app disagree on these squares and no short sequence explains it. */
        data class Mismatch(val squares: List<Int>) : Verdict
        data object Unsure : Verdict
    }

    var debug = ""
    private var lastExplained: Verdict.Explained? = null
    private var explainedStreak = 0
    private var mismatchStreak = 0

    fun reset() {
        lastExplained = null
        explainedStreak = 0
        mismatchStreak = 0
    }

    /**
     * Raw judgement of one scan (no streaks).
     * @param board the app position; its move history is used to consider undoing the last move
     */
    fun judge(scan: BoardScan, orientation: Int, board: Board): Verdict {
        val e = PositionGuesser.Evidence(scan.occupancy(orientation), scan.color(orientation))
        fun cost(b: Board): Double {
            var t = 0.0
            for (sq in 0 until 64) t += PositionGuesser.squareCost(e, sq, b.squares[sq])
            return t
        }

        val current = cost(board)
        // Best explanation, scored in place (no board copies: they carry the whole undo history).
        var best: Verdict.Explained? = null
        var bestSquares: IntArray? = null
        var bestCost = Double.MAX_VALUE
        // Runner-up reaching a different position: an explanation must clearly beat it.
        var secondCost = Double.MAX_VALUE
        val work = board.copy()
        fun consider(v: Verdict.Explained) {
            val k = cost(work)
            if (k >= secondCost) return
            val same = bestSquares?.contentEquals(work.squares) == true
            if (k < bestCost) {
                if (!same) secondCost = bestCost
                bestCost = k; best = v; bestSquares = work.squares.copyOf()
            } else if (!same) {
                secondCost = k
            }
        }
        fun successors(undo: Int, prefix: List<Int>, depth: Int) {
            if (depth == 0) return
            for (m in work.legalMoves()) {
                work.makeMove(m)
                val moves = prefix + m
                consider(Verdict.Explained(undo, moves))
                successors(undo, moves, depth - 1)
                work.unmakeMove()
            }
        }
        successors(0, emptyList(), 2)
        if (work.plyCount > 0) {
            val last = work.lastMove()
            work.unmakeMove()
            consider(Verdict.Explained(1, emptyList()))
            // The last move was misread: a different move from the same position.
            for (m in work.legalMoves()) {
                if (m == last) continue
                work.makeMove(m)
                consider(Verdict.Explained(1, listOf(m)))
                work.unmakeMove()
            }
            work.makeMove(last)
        }
        val b = bestSquares
        debug = "cur=${"%.1f".format(current)} best=${"%.1f".format(bestCost)} ${best?.moves?.map { com.chesslens.chess.Move.uci(it) }} u${best?.undo} 2nd=${"%.1f".format(secondCost)}" +
            (b?.let { bb -> " sup=" + (0 until 64).filter { bb[it] != board.squares[it] }.joinToString(",") { sq -> com.chesslens.chess.Square.name(sq) + ":" + "%.1f".format(PositionGuesser.squareCost(e, sq, board.squares[sq]) - PositionGuesser.squareCost(e, sq, bb[sq])) } } ?: "")
        if (best != null && b != null && current - bestCost >= MARGIN && secondCost - bestCost >= RUNNER_UP_MARGIN) {
            // No square where the explanation differs may contradict it, and some must back it
            // clearly (squares hidden behind tall pieces only give weak evidence either way).
            val support = (0 until 64).filter { b[it] != board.squares[it] }.map { sq ->
                PositionGuesser.squareCost(e, sq, board.squares[sq]) - PositionGuesser.squareCost(e, sq, b[sq])
            }
            if (support.all { it >= -0.2 } && support.count { it >= 1.0 } >= minOf(2, support.size) && support.sum() >= MARGIN) return best!!
        }
        val contradicted = (0 until 64).filter { PositionGuesser.squareCost(e, it, board.squares[it]) >= STRONG }
        return when {
            contradicted.size >= MISMATCH_SQUARES -> Verdict.Mismatch(contradicted)
            contradicted.isEmpty() -> Verdict.Ok
            else -> Verdict.Unsure
        }
    }

    /** [judge] plus persistence: acts only on verdicts repeated over consecutive checks. */
    fun update(scan: BoardScan, orientation: Int, board: Board): Verdict {
        val v = judge(scan, orientation, board)
        when (v) {
            is Verdict.Explained -> {
                mismatchStreak = 0
                explainedStreak = if (v == lastExplained) explainedStreak + 1 else 1
                lastExplained = v
                if (explainedStreak >= 2) {
                    reset()
                    return v
                }
                return Verdict.Unsure
            }
            is Verdict.Mismatch -> {
                explainedStreak = 0
                lastExplained = null
                return if (++mismatchStreak >= 3) v else Verdict.Unsure
            }
            Verdict.Ok -> {
                reset()
                return v
            }
            Verdict.Unsure -> {
                explainedStreak = 0
                lastExplained = null
                return v
            }
        }
    }

    companion object {
        /** Total evidence an explanation must gain over the app position. */
        private const val MARGIN = 3.0
        /** How much better than the next different explanation it must be. */
        private const val RUNNER_UP_MARGIN = 1.0
        /** A square whose evidence clearly contradicts the app position. */
        private const val STRONG = 2.5
        private const val MISMATCH_SQUARES = 3
    }
}
