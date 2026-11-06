package com.chesslens.chess

/** One played move with everything the UI needs to display it. */
data class PlayedMove(
    val move: Int,
    val san: String,
    val spoken: String,
    /** Colour that played the move. */
    val color: Int,
    /** FEN of the position after the move. */
    val fenAfter: String,
    val moveNumber: Int,
)

/**
 * Immutable-feeling game record. Positions are rebuilt by replaying from [startFen]
 * which keeps repetition history correct.
 */
class Game(val startFen: String = Board.START_FEN) {
    private val _moves = ArrayList<PlayedMove>()
    val moves: List<PlayedMove> get() = _moves
    private var board: Board = Board.fromFen(startFen)

    /** Live board for the end of the game (do not mutate from outside). */
    val current: Board get() = board

    fun boardAt(ply: Int): Board {
        val b = Board.fromFen(startFen)
        for (i in 0 until ply.coerceIn(0, _moves.size)) b.makeMove(_moves[i].move)
        return b
    }

    fun play(move: Int): PlayedMove? {
        if (move !in board.legalMoves()) return null
        val san = San.of(board, move)
        val spoken = San.spoken(board, move)
        val color = board.side
        val number = board.fullmoveNumber
        board.makeMove(move)
        val pm = PlayedMove(move, san, spoken, color, board.toFen(), number)
        _moves += pm
        return pm
    }

    fun undo(): PlayedMove? {
        if (_moves.isEmpty()) return null
        val last = _moves.removeAt(_moves.size - 1)
        board.unmakeMove()
        return last
    }

    fun copy(): Game {
        val g = Game(startFen)
        for (m in _moves) g.play(m.move)
        return g
    }

    fun pgn(white: String = "White", black: String = "Black"): String {
        val sb = StringBuilder()
        sb.append("[Event \"ChessLens game\"]\n[White \"$white\"]\n[Black \"$black\"]\n")
        if (startFen != Board.START_FEN) sb.append("[SetUp \"1\"]\n[FEN \"$startFen\"]\n")
        val result = when (board.status()) {
            GameStatus.CHECKMATE -> if (board.side == Piece.WHITE) "0-1" else "1-0"
            GameStatus.ONGOING -> "*"
            else -> "1/2-1/2"
        }
        sb.append("[Result \"$result\"]\n\n")
        for ((i, m) in _moves.withIndex()) {
            if (m.color == Piece.WHITE) sb.append(m.moveNumber).append(". ")
            else if (i == 0) sb.append(m.moveNumber).append("... ")
            sb.append(m.san).append(' ')
        }
        sb.append(result)
        return sb.toString()
    }
}
