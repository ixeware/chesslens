package com.chesslens.chess

object San {
    /** Standard algebraic notation for a legal move [m] in position [board]. */
    fun of(board: Board, m: Int): String {
        val sb = StringBuilder()
        val from = Move.from(m)
        val to = Move.to(m)
        val pc = board.pieceAt(from)
        val type = Piece.type(pc)
        if (Move.isCastle(m)) {
            sb.append(if (Square.file(to) == 6) "O-O" else "O-O-O")
        } else {
            if (type == Piece.PAWN) {
                if (Move.isCapture(m)) sb.append('a' + Square.file(from)).append('x')
                sb.append(Square.name(to))
                if (Move.promo(m) != 0) sb.append('=').append(Piece.letter(Move.promo(m)))
            } else {
                sb.append(Piece.letter(type))
                val rivals = board.legalMoves().filter {
                    it != m && Move.to(it) == to && board.pieceAt(Move.from(it)) == pc
                }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { Square.file(Move.from(it)) == Square.file(from) }
                    val sameRank = rivals.any { Square.rank(Move.from(it)) == Square.rank(from) }
                    when {
                        !sameFile -> sb.append('a' + Square.file(from))
                        !sameRank -> sb.append('1' + Square.rank(from))
                        else -> sb.append(Square.name(from))
                    }
                }
                if (Move.isCapture(m)) sb.append('x')
                sb.append(Square.name(to))
            }
        }
        if (board.makeMove(m)) {
            if (board.inCheck()) sb.append(if (board.hasLegalMove()) '+' else '#')
            board.unmakeMove()
        }
        return sb.toString()
    }

    /** Parses SAN (tolerant of missing check marks / annotations). Returns null if illegal. */
    fun parse(board: Board, sanIn: String): Int? {
        val san = sanIn.trim().replace(Regex("[+#!?]+$"), "").replace("0", "O")
        val legal = board.legalMoves()
        if (san == "O-O" || san == "O-O-O") {
            val toFile = if (san == "O-O") 6 else 2
            return legal.firstOrNull { Move.isCastle(it) && Square.file(Move.to(it)) == toFile }
        }
        return legal.firstOrNull { of(board, it).replace(Regex("[+#]$"), "") == san }
            ?: legal.firstOrNull { Move.uci(it) == sanIn.trim().lowercase() }
    }

    /**
     * Human readable spoken description, e.g. "Knight to f6", "Bishop takes d2, check",
     * "Castles kingside", "Pawn to e8, promotes to queen".
     */
    fun spoken(board: Board, m: Int): String {
        val from = Move.from(m)
        val to = Move.to(m)
        val type = Piece.type(board.pieceAt(from))
        val sb = StringBuilder()
        if (Move.isCastle(m)) {
            sb.append(if (Square.file(to) == 6) "Castles kingside" else "Castles queenside")
        } else {
            sb.append(Piece.name(type))
            sb.append(if (Move.isCapture(m)) " takes " else " to ")
            sb.append(Square.name(to).uppercase())
            if (Move.promo(m) != 0) sb.append(", promotes to ").append(Piece.name(Move.promo(m)).lowercase())
        }
        if (board.makeMove(m)) {
            if (board.inCheck()) sb.append(if (board.hasLegalMove()) ", check" else ", checkmate")
            board.unmakeMove()
        }
        return sb.toString()
    }

    /** Short caption like "Knight to E4" used under the big move text. */
    fun caption(board: Board, m: Int): String {
        val type = Piece.type(board.pieceAt(Move.from(m)))
        if (Move.isCastle(m)) return if (Square.file(Move.to(m)) == 6) "Castle kingside" else "Castle queenside"
        val verb = if (Move.isCapture(m)) "takes" else "to"
        return "${Piece.name(type)} $verb ${Square.name(Move.to(m)).uppercase()}"
    }
}
