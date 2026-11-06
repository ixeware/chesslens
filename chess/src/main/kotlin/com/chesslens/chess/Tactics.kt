package com.chesslens.chess

import kotlin.math.exp

/** Static tactical helpers used by the analysis overlay and the move classifier. */
object Tactics {

    /**
     * Static exchange evaluation of the capture sequence on [square] started by side [by]
     * (without playing any move). Returns material gained by [by] in centipawns (>= 0 when the
     * first capture is good, can be negative). Uses x-ray aware attacker recomputation.
     */
    fun exchangeOn(board: Board, square: Int, by: Int): Int {
        val bd = board.squares.copyOf()
        val target = bd[square]
        if (target == 0) return 0
        return seeRec(board, bd, square, by, Piece.VALUE[Piece.type(target)]).coerceAtLeast(Int.MIN_VALUE / 2)
    }

    private fun seeRec(board: Board, bd: IntArray, sq: Int, side: Int, captureValue: Int): Int {
        val attackers = board.attackers(sq, side, bd)
        if (attackers.isEmpty()) return 0
        val from = attackers.minBy { Piece.VALUE[Piece.type(bd[it])] }
        val attacker = bd[from]
        // King may only capture if the square isn't defended afterwards.
        bd[from] = 0
        val saved = bd[sq]
        bd[sq] = attacker
        val reply = if (Piece.type(attacker) == Piece.KING && board.attackers(sq, side xor 1, bd).isNotEmpty()) {
            bd[sq] = saved
            bd[from] = attacker
            return 0
        } else {
            seeRec(board, bd, sq, side xor 1, Piece.VALUE[Piece.type(attacker)])
        }
        bd[sq] = saved
        bd[from] = attacker
        return maxOf(0, captureValue - reply)
    }

    /** SEE of move [m] for the mover: material balance after the exchange it starts. */
    fun see(board: Board, m: Int): Int {
        val from = Move.from(m)
        val to = Move.to(m)
        val mover = board.pieceAt(from)
        val us = Piece.color(mover)
        val captured = when {
            Move.isEnPassant(m) -> Piece.VALUE[Piece.PAWN]
            board.pieceAt(to) != 0 -> Piece.VALUE[Piece.type(board.pieceAt(to))]
            else -> 0
        }
        val bd = board.squares.copyOf()
        bd[from] = 0
        bd[to] = if (Move.promo(m) != 0) Piece.make(us, Move.promo(m)) else mover
        if (Move.isEnPassant(m)) bd[if (us == Piece.WHITE) to - 8 else to + 8] = 0
        val onSquare = Piece.VALUE[Piece.type(bd[to])]
        val promoGain = if (Move.promo(m) != 0) Piece.VALUE[Move.promo(m)] - Piece.VALUE[Piece.PAWN] else 0
        val reply = seeRec(board, bd, to, us xor 1, onSquare)
        return captured + promoGain - reply
    }

    /** Pieces of [color] that are attacked and either undefended or attacked by cheaper pieces. */
    fun hangingPieces(board: Board, color: Int): List<Int> {
        val out = ArrayList<Int>()
        for (sq in 0 until 64) {
            val p = board.pieceAt(sq)
            if (p == 0 || Piece.color(p) != color || Piece.type(p) == Piece.KING) continue
            if (exchangeOn(board, sq, color xor 1) >= 100) out += sq
        }
        return out
    }

    /** Control map: (#white attackers - #black attackers) for every square. */
    fun controlMap(board: Board): IntArray = IntArray(64) { sq ->
        board.attackers(sq, Piece.WHITE).size - board.attackers(sq, Piece.BLACK).size
    }

    /** Lichess-style win probability (0..100) for a centipawn score. */
    fun winPercent(cp: Int): Double {
        val c = cp.coerceIn(-1500, 1500)
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * c)) - 1.0)
    }
}

/** Move quality; [symbol] is the chess annotation mark, empty for qualities that have none. */
enum class MoveQuality(val symbol: String, val label: String, val argb: Long) {
    BRILLIANT("!!", "Brilliant", 0xFF1BC5C9),
    GREAT("!", "Great move", 0xFF5B8CFF),
    BEST("", "Best move", 0xFF6EE787),
    EXCELLENT("", "Excellent", 0xFF8BD66B),
    GOOD("", "Good", 0xFF9FC28A),
    BOOK("", "Book", 0xFFC9A57B),
    INACCURACY("?!", "Inaccuracy", 0xFFF2C94C),
    MISTAKE("?", "Mistake", 0xFFF59E42),
    BLUNDER("??", "Blunder", 0xFFFF5A5A),
}

object MoveClassifier {
    /**
     * @param before engine result for the position before the move (side to move = mover)
     * @param after engine result for the position after the move
     * @param boardBefore the position before the move
     * @param move the move that was played
     */
    fun classify(before: EngineInfo, after: EngineInfo, boardBefore: Board, move: Int, ply: Int): MoveQuality {
        val mover = boardBefore.side
        val bestLine = before.best ?: return MoveQuality.GOOD
        val bestCp = povCp(before, bestLine, mover)
        val playedCp = if (after.lines.isEmpty()) {
            // Game over after the move: mate is perfect, stalemate is a draw.
            val bd = boardBefore.copy()
            bd.makeMove(move)
            if (bd.inCheck()) 10000 else 0
        } else {
            povCp(after, after.best, mover)
        }
        val loss = Tactics.winPercent(bestCp) - Tactics.winPercent(playedCp)
        val isBest = move == bestLine.move || loss <= 0.5

        if (ply < 8 && loss < 3.0 && OPENING_SANS.contains(San.of(boardBefore, move))) return MoveQuality.BOOK

        if (isBest) {
            val seeValue = Tactics.see(boardBefore, move)
            val sacrifice = seeValue <= -200
            val moverWin = Tactics.winPercent(playedCp)
            if (sacrifice && moverWin >= 50 && Tactics.winPercent(bestCp) < 97) return MoveQuality.BRILLIANT
            val second = before.lines.getOrNull(1)
            if (second != null) {
                val gap = Tactics.winPercent(bestCp) - Tactics.winPercent(povCp(before, second, mover))
                if (gap >= 15 && moverWin in 30.0..97.0) return MoveQuality.GREAT
            }
            return MoveQuality.BEST
        }
        return when {
            loss < 2.0 -> MoveQuality.EXCELLENT
            loss < 5.0 -> MoveQuality.GOOD
            loss < 10.0 -> MoveQuality.INACCURACY
            loss < 20.0 -> MoveQuality.MISTAKE
            else -> MoveQuality.BLUNDER
        }
    }

    private fun povCp(info: EngineInfo, line: EngineLine?, pov: Int): Int {
        val white = info.whiteCp(line)
        return if (pov == Piece.WHITE) white else -white
    }

    /** A tiny set of mainstream opening moves so the first moves read as "Book". */
    private val OPENING_SANS = setOf(
        "e4", "d4", "c4", "Nf3", "e5", "c5", "e6", "c6", "d5", "Nf6", "g6", "d6",
        "Nc3", "Nc6", "Bc4", "Bb5", "Bg5", "Bf4", "g3", "Bg2", "Bg7", "Be7", "O-O", "a6", "Bb4", "Be2",
        "b3", "Bb2", "f4", "Qe2", "Re1", "h3", "Be3", "Bd3", "Bc5", "b6", "Bb7", "Nbd7", "Nd7",
    )
}
