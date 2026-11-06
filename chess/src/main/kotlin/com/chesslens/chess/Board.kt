package com.chesslens.chess

import com.chesslens.chess.Piece.BISHOP
import com.chesslens.chess.Piece.BLACK
import com.chesslens.chess.Piece.KING
import com.chesslens.chess.Piece.KNIGHT
import com.chesslens.chess.Piece.PAWN
import com.chesslens.chess.Piece.QUEEN
import com.chesslens.chess.Piece.ROOK
import com.chesslens.chess.Piece.WHITE

/**
 * Mailbox chess position with make/unmake, legal move generation, FEN and Zobrist hashing.
 * Not thread safe: give each thread its own [copy].
 */
class Board private constructor() {
    val squares = IntArray(64)
    var side = WHITE
        private set
    /** Bit mask: 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O. */
    var castling = 0
        private set
    /** En-passant target square or -1 (only set when a capture is actually possible). */
    var epSquare = -1
        private set
    var halfmoveClock = 0
        private set
    var fullmoveNumber = 1
        private set
    var key = 0L
        private set
    private val kingSq = IntArray(2)

    // ---- undo stack -------------------------------------------------------------------------
    private var sp = 0
    private val uMove = IntArray(MAX_HISTORY)
    private val uCaptured = IntArray(MAX_HISTORY)
    private val uCastling = IntArray(MAX_HISTORY)
    private val uEp = IntArray(MAX_HISTORY)
    private val uHalf = IntArray(MAX_HISTORY)
    private val uKey = LongArray(MAX_HISTORY)

    /** Keys of all earlier positions (game + search) for repetition detection. */
    private val keyHistory = LongArray(MAX_HISTORY + 1)

    fun kingSquare(color: Int): Int = kingSq[color]
    fun pieceAt(sq: Int): Int = squares[sq]

    /** Number of plies made on this board object (game prefix + current search line). */
    val plyCount: Int get() = sp

    // ---- FEN --------------------------------------------------------------------------------

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = squares[Square.of(f, r)]
                if (p == 0) {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty).also { empty = 0 }
                    sb.append(Piece.fenChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (side == WHITE) " w " else " b ")
        if (castling == 0) sb.append('-') else {
            if (castling and 1 != 0) sb.append('K')
            if (castling and 2 != 0) sb.append('Q')
            if (castling and 4 != 0) sb.append('k')
            if (castling and 8 != 0) sb.append('q')
        }
        sb.append(' ').append(if (epSquare >= 0) Square.name(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    /** Placement part of the FEN only (used to compare positions irrespective of clocks). */
    fun placementFen(): String = toFen().substringBefore(' ')

    fun copy(): Board {
        val b = Board()
        b.loadFrom(this)
        return b
    }

    private fun loadFrom(o: Board) {
        o.squares.copyInto(squares)
        side = o.side
        castling = o.castling
        epSquare = o.epSquare
        halfmoveClock = o.halfmoveClock
        fullmoveNumber = o.fullmoveNumber
        key = o.key
        kingSq[0] = o.kingSq[0]
        kingSq[1] = o.kingSq[1]
        sp = o.sp
        o.uMove.copyInto(uMove, 0, 0, sp)
        o.uCaptured.copyInto(uCaptured, 0, 0, sp)
        o.uCastling.copyInto(uCastling, 0, 0, sp)
        o.uEp.copyInto(uEp, 0, 0, sp)
        o.uHalf.copyInto(uHalf, 0, 0, sp)
        o.uKey.copyInto(uKey, 0, 0, sp)
        o.keyHistory.copyInto(keyHistory, 0, 0, sp + 1)
    }

    private fun computeKey(): Long {
        var k = 0L
        for (s in 0 until 64) if (squares[s] != 0) k = k xor Zobrist.piece[squares[s]][s]
        if (side == BLACK) k = k xor Zobrist.side
        k = k xor Zobrist.castling[castling]
        if (epSquare >= 0) k = k xor Zobrist.epFile[Square.file(epSquare)]
        return k
    }

    // ---- attacks ----------------------------------------------------------------------------

    /** True if [sq] is attacked by any piece of colour [by]. */
    fun isAttacked(sq: Int, by: Int): Boolean = isAttacked(sq, by, squares)

    private fun isAttacked(sq: Int, by: Int, bd: IntArray): Boolean = attackedOn(bd, sq, by)

    /** All squares holding pieces of colour [by] that attack [sq] on board [bd]. */
    fun attackers(sq: Int, by: Int, bd: IntArray = squares): List<Int> {
        val out = ArrayList<Int>(4)
        val pawn = Piece.make(by, PAWN)
        for (p in Tables.pawnAttacks[by xor 1][sq]) if (bd[p] == pawn) out += p
        val knight = Piece.make(by, KNIGHT)
        for (p in Tables.knight[sq]) if (bd[p] == knight) out += p
        val king = Piece.make(by, KING)
        for (p in Tables.king[sq]) if (bd[p] == king) out += p
        val rook = Piece.make(by, ROOK)
        val bishop = Piece.make(by, BISHOP)
        val queen = Piece.make(by, QUEEN)
        for (d in 0 until 8) {
            val straight = d < 4
            for (t in Tables.rays[d][sq]) {
                val p = bd[t]
                if (p == 0) continue
                if (p == queen || (straight && p == rook) || (!straight && p == bishop)) out += t
                break
            }
        }
        return out
    }

    fun inCheck(): Boolean = isAttacked(kingSq[side], side xor 1)

    // ---- move generation ----------------------------------------------------------------------

    /** Writes pseudo-legal moves into [out]; returns count. */
    fun generatePseudo(out: IntArray, capturesOnly: Boolean = false): Int {
        var n = 0
        val us = side
        val them = us xor 1
        val forward = if (us == WHITE) 8 else -8
        val startRank = if (us == WHITE) 1 else 6
        val promoRank = if (us == WHITE) 7 else 0
        for (from in 0 until 64) {
            val pc = squares[from]
            if (pc == 0 || Piece.color(pc) != us) continue
            when (Piece.type(pc)) {
                PAWN -> {
                    val one = from + forward
                    if (squares[one] == 0) {
                        if (Square.rank(one) == promoRank) {
                            out[n++] = Move.make(from, one, QUEEN)
                            if (!capturesOnly) {
                                out[n++] = Move.make(from, one, ROOK)
                                out[n++] = Move.make(from, one, BISHOP)
                                out[n++] = Move.make(from, one, KNIGHT)
                            }
                        } else if (!capturesOnly) {
                            out[n++] = Move.make(from, one)
                            if (Square.rank(from) == startRank) {
                                val two = one + forward
                                if (squares[two] == 0) out[n++] = Move.make(from, two, 0, Move.F_DOUBLE)
                            }
                        }
                    }
                    for (to in Tables.pawnAttacks[us][from]) {
                        val t = squares[to]
                        if (t != 0 && Piece.color(t) == them) {
                            if (Square.rank(to) == promoRank) {
                                out[n++] = Move.make(from, to, QUEEN, Move.F_CAPTURE)
                                if (!capturesOnly) {
                                    out[n++] = Move.make(from, to, ROOK, Move.F_CAPTURE)
                                    out[n++] = Move.make(from, to, BISHOP, Move.F_CAPTURE)
                                    out[n++] = Move.make(from, to, KNIGHT, Move.F_CAPTURE)
                                }
                            } else {
                                out[n++] = Move.make(from, to, 0, Move.F_CAPTURE)
                            }
                        } else if (to == epSquare) {
                            out[n++] = Move.make(from, to, 0, Move.F_CAPTURE or Move.F_EP)
                        }
                    }
                }
                KNIGHT -> n = stepMoves(from, Tables.knight[from], out, n, capturesOnly)
                KING -> {
                    n = stepMoves(from, Tables.king[from], out, n, capturesOnly)
                    if (!capturesOnly) n = castleMoves(out, n)
                }
                BISHOP -> n = slideMoves(from, 4, 8, out, n, capturesOnly)
                ROOK -> n = slideMoves(from, 0, 4, out, n, capturesOnly)
                QUEEN -> n = slideMoves(from, 0, 8, out, n, capturesOnly)
            }
        }
        return n
    }

    private fun stepMoves(from: Int, targets: IntArray, out: IntArray, start: Int, capturesOnly: Boolean): Int {
        var n = start
        for (to in targets) {
            val t = squares[to]
            if (t == 0) {
                if (!capturesOnly) out[n++] = Move.make(from, to)
            } else if (Piece.color(t) != side) {
                out[n++] = Move.make(from, to, 0, Move.F_CAPTURE)
            }
        }
        return n
    }

    private fun slideMoves(from: Int, d0: Int, d1: Int, out: IntArray, start: Int, capturesOnly: Boolean): Int {
        var n = start
        for (d in d0 until d1) {
            for (to in Tables.rays[d][from]) {
                val t = squares[to]
                if (t == 0) {
                    if (!capturesOnly) out[n++] = Move.make(from, to)
                } else {
                    if (Piece.color(t) != side) out[n++] = Move.make(from, to, 0, Move.F_CAPTURE)
                    break
                }
            }
        }
        return n
    }

    private fun castleMoves(out: IntArray, start: Int): Int {
        var n = start
        val them = side xor 1
        if (side == WHITE) {
            if (castling and 1 != 0 && squares[5] == 0 && squares[6] == 0 && squares[7] == Piece.make(WHITE, ROOK) &&
                !isAttacked(4, them) && !isAttacked(5, them) && !isAttacked(6, them)
            ) out[n++] = Move.make(4, 6, 0, Move.F_CASTLE)
            if (castling and 2 != 0 && squares[3] == 0 && squares[2] == 0 && squares[1] == 0 &&
                squares[0] == Piece.make(WHITE, ROOK) &&
                !isAttacked(4, them) && !isAttacked(3, them) && !isAttacked(2, them)
            ) out[n++] = Move.make(4, 2, 0, Move.F_CASTLE)
        } else {
            if (castling and 4 != 0 && squares[61] == 0 && squares[62] == 0 && squares[63] == Piece.make(BLACK, ROOK) &&
                !isAttacked(60, them) && !isAttacked(61, them) && !isAttacked(62, them)
            ) out[n++] = Move.make(60, 62, 0, Move.F_CASTLE)
            if (castling and 8 != 0 && squares[59] == 0 && squares[58] == 0 && squares[57] == 0 &&
                squares[56] == Piece.make(BLACK, ROOK) &&
                !isAttacked(60, them) && !isAttacked(59, them) && !isAttacked(58, them)
            ) out[n++] = Move.make(60, 58, 0, Move.F_CASTLE)
        }
        return n
    }

    fun legalMoves(): List<Int> {
        val buf = IntArray(256)
        val n = generatePseudo(buf)
        val out = ArrayList<Int>(n)
        for (i in 0 until n) {
            if (makeMove(buf[i])) {
                unmakeMove()
                out += buf[i]
            }
        }
        return out
    }

    fun hasLegalMove(): Boolean {
        val buf = IntArray(256)
        val n = generatePseudo(buf)
        for (i in 0 until n) {
            if (makeMove(buf[i])) {
                unmakeMove()
                return true
            }
        }
        return false
    }

    // ---- make / unmake ----------------------------------------------------------------------

    /**
     * Plays a pseudo-legal move. Returns false (and leaves the board unchanged) if it would
     * leave the mover's king in check.
     */
    fun makeMove(m: Int): Boolean {
        val from = Move.from(m)
        val to = Move.to(m)
        val pc = squares[from]
        val us = side
        val them = us xor 1

        uMove[sp] = m
        uCastling[sp] = castling
        uEp[sp] = epSquare
        uHalf[sp] = halfmoveClock
        uKey[sp] = key
        keyHistory[sp] = key

        var k = key
        if (epSquare >= 0) k = k xor Zobrist.epFile[Square.file(epSquare)]
        k = k xor Zobrist.castling[castling]

        var captured = 0
        if (Move.isEnPassant(m)) {
            val capSq = if (us == WHITE) to - 8 else to + 8
            captured = squares[capSq]
            squares[capSq] = 0
            k = k xor Zobrist.piece[captured][capSq]
        } else if (squares[to] != 0) {
            captured = squares[to]
            k = k xor Zobrist.piece[captured][to]
        }
        uCaptured[sp] = captured

        squares[from] = 0
        k = k xor Zobrist.piece[pc][from]
        val placed = if (Move.promo(m) != 0) Piece.make(us, Move.promo(m)) else pc
        squares[to] = placed
        k = k xor Zobrist.piece[placed][to]

        if (Piece.type(pc) == KING) {
            kingSq[us] = to
            if (Move.isCastle(m)) {
                val (rf, rt) = when (to) {
                    6 -> 7 to 5
                    2 -> 0 to 3
                    62 -> 63 to 61
                    else -> 56 to 59
                }
                val rook = squares[rf]
                squares[rf] = 0
                squares[rt] = rook
                k = k xor Zobrist.piece[rook][rf] xor Zobrist.piece[rook][rt]
            }
        }

        castling = castling and CASTLE_MASK[from] and CASTLE_MASK[to]
        k = k xor Zobrist.castling[castling]

        epSquare = -1
        if (Move.flags(m) and Move.F_DOUBLE != 0) {
            val epCandidate = (from + to) / 2
            val enemyPawn = Piece.make(them, PAWN)
            // Only record the ep square if an enemy pawn could capture onto it.
            for (p in Tables.pawnAttacks[us][epCandidate]) {
                if (squares[p] == enemyPawn) {
                    epSquare = epCandidate
                    break
                }
            }
            if (epSquare >= 0) k = k xor Zobrist.epFile[Square.file(epSquare)]
        }

        halfmoveClock = if (Piece.type(pc) == PAWN || captured != 0) 0 else halfmoveClock + 1
        if (us == BLACK) fullmoveNumber++
        side = them
        k = k xor Zobrist.side
        key = k
        sp++

        if (isAttacked(kingSq[us], them)) {
            unmakeMove()
            return false
        }
        return true
    }

    fun unmakeMove() {
        sp--
        val m = uMove[sp]
        val from = Move.from(m)
        val to = Move.to(m)
        side = side xor 1
        val us = side
        if (us == BLACK) fullmoveNumber--

        var pc = squares[to]
        if (Move.promo(m) != 0) pc = Piece.make(us, PAWN)
        squares[from] = pc
        squares[to] = 0
        val captured = uCaptured[sp]
        if (Move.isEnPassant(m)) {
            squares[if (us == WHITE) to - 8 else to + 8] = captured
        } else {
            squares[to] = captured
        }
        if (Piece.type(pc) == KING) {
            kingSq[us] = from
            if (Move.isCastle(m)) {
                val (rf, rt) = when (to) {
                    6 -> 7 to 5
                    2 -> 0 to 3
                    62 -> 63 to 61
                    else -> 56 to 59
                }
                squares[rf] = squares[rt]
                squares[rt] = 0
            }
        }
        castling = uCastling[sp]
        epSquare = uEp[sp]
        halfmoveClock = uHalf[sp]
        key = uKey[sp]
    }

    fun makeNullMove() {
        uMove[sp] = Move.NONE
        uCaptured[sp] = 0
        uCastling[sp] = castling
        uEp[sp] = epSquare
        uHalf[sp] = halfmoveClock
        uKey[sp] = key
        keyHistory[sp] = key
        var k = key
        if (epSquare >= 0) k = k xor Zobrist.epFile[Square.file(epSquare)]
        epSquare = -1
        side = side xor 1
        k = k xor Zobrist.side
        key = k
        halfmoveClock++
        sp++
    }

    fun unmakeNullMove() {
        sp--
        side = side xor 1
        epSquare = uEp[sp]
        halfmoveClock = uHalf[sp]
        key = uKey[sp]
    }

    /** Last move made on this board (or [Move.NONE]). */
    fun lastMove(): Int = if (sp > 0) uMove[sp - 1] else Move.NONE

    // ---- draws ------------------------------------------------------------------------------

    /** True if the current position occurred before within the reversible-move window. */
    fun isRepetition(minCount: Int = 1): Boolean {
        var count = 0
        var i = sp - 2
        val limit = maxOf(0, sp - halfmoveClock)
        while (i >= limit) {
            if (keyHistory[i] == key) {
                count++
                if (count >= minCount) return true
            }
            i -= 2
        }
        return false
    }

    fun isInsufficientMaterial(): Boolean {
        var minors = 0
        for (p in squares) {
            when (Piece.type(p)) {
                PAWN, ROOK, QUEEN -> return false
                KNIGHT, BISHOP -> minors++
            }
        }
        return minors <= 1
    }

    fun hasNonPawnMaterial(color: Int): Boolean {
        for (p in squares) {
            if (p != 0 && Piece.color(p) == color) {
                val t = Piece.type(p)
                if (t != PAWN && t != KING) return true
            }
        }
        return false
    }

    fun status(): GameStatus {
        val any = hasLegalMove()
        return when {
            !any && inCheck() -> GameStatus.CHECKMATE
            !any -> GameStatus.STALEMATE
            halfmoveClock >= 100 -> GameStatus.FIFTY_MOVES
            isRepetition(2) -> GameStatus.THREEFOLD
            isInsufficientMaterial() -> GameStatus.INSUFFICIENT
            else -> GameStatus.ONGOING
        }
    }

    /** Finds the legal move from->to (queen promotion by default). */
    fun findMove(from: Int, to: Int, promo: Int = QUEEN): Int? {
        val moves = legalMoves().filter { Move.from(it) == from && Move.to(it) == to }
        if (moves.isEmpty()) return null
        return moves.firstOrNull { Move.promo(it) == 0 || Move.promo(it) == promo } ?: moves.first()
    }

    fun parseUci(uci: String): Int? {
        if (uci.length < 4) return null
        val from = runCatching { Square.parse(uci.substring(0, 2)) }.getOrNull() ?: return null
        val to = runCatching { Square.parse(uci.substring(2, 4)) }.getOrNull() ?: return null
        val promo = if (uci.length > 4) " pnbrqk".indexOf(uci[4].lowercaseChar()) else 0
        return legalMoves().firstOrNull {
            Move.from(it) == from && Move.to(it) == to && Move.promo(it) == maxOf(promo, 0)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            for (f in 0..7) {
                val p = squares[Square.of(f, r)]
                sb.append(if (p == 0) '.' else Piece.fenChar(p)).append(' ')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val MAX_HISTORY = 2048

        private val CASTLE_MASK = IntArray(64) { 15 }.also {
            it[0] = 15 and 2.inv()
            it[4] = 15 and 3.inv()
            it[7] = 15 and 1.inv()
            it[56] = 15 and 8.inv()
            it[60] = 15 and 12.inv()
            it[63] = 15 and 4.inv()
        }

        fun startPosition(): Board = fromFen(START_FEN)

        /** True if [sq] is attacked by colour [by] on the placement [bd]. */
        fun attackedOn(bd: IntArray, sq: Int, by: Int): Boolean {
            val pawn = Piece.make(by, PAWN)
            for (p in Tables.pawnAttacks[by xor 1][sq]) if (bd[p] == pawn) return true
            val knight = Piece.make(by, KNIGHT)
            for (p in Tables.knight[sq]) if (bd[p] == knight) return true
            val king = Piece.make(by, KING)
            for (p in Tables.king[sq]) if (bd[p] == king) return true
            val rook = Piece.make(by, ROOK)
            val bishop = Piece.make(by, BISHOP)
            val queen = Piece.make(by, QUEEN)
            for (d in 0 until 8) {
                val straight = d < 4
                for (t in Tables.rays[d][sq]) {
                    val p = bd[t]
                    if (p == 0) continue
                    if (p == queen || (straight && p == rook) || (!straight && p == bishop)) return true
                    break
                }
            }
            return false
        }


        fun fromFen(fen: String): Board {
            val b = Board()
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty()) { "Empty FEN" }
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN needs 8 ranks" }
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) {
                        f += c - '0'
                    } else {
                        require(f < 8) { "Rank overflow in FEN" }
                        b.squares[Square.of(f, r)] = Piece.fromFenChar(c)
                        f++
                    }
                }
                require(f == 8) { "Rank ${r + 1} has $f files" }
            }
            b.side = if (parts.getOrNull(1) == "b") BLACK else WHITE
            val c = parts.getOrNull(2) ?: "-"
            b.castling = (if ('K' in c) 1 else 0) or (if ('Q' in c) 2 else 0) or
                (if ('k' in c) 4 else 0) or (if ('q' in c) 8 else 0)
            val ep = parts.getOrNull(3) ?: "-"
            b.epSquare = if (ep == "-") -1 else Square.parse(ep)
            b.halfmoveClock = parts.getOrNull(4)?.toIntOrNull() ?: 0
            b.fullmoveNumber = parts.getOrNull(5)?.toIntOrNull() ?: 1
            var wk = -1
            var bk = -1
            for (s in 0 until 64) {
                if (b.squares[s] == Piece.make(WHITE, KING)) wk = s
                if (b.squares[s] == Piece.make(BLACK, KING)) bk = s
            }
            require(wk >= 0 && bk >= 0) { "Both kings are required" }
            b.kingSq[WHITE] = wk
            b.kingSq[BLACK] = bk
            // Sanitize castling rights against piece placement.
            if (wk != 4) b.castling = b.castling and 3.inv()
            if (bk != 60) b.castling = b.castling and 12.inv()
            if (b.squares[7] != Piece.make(WHITE, ROOK)) b.castling = b.castling and 1.inv()
            if (b.squares[0] != Piece.make(WHITE, ROOK)) b.castling = b.castling and 2.inv()
            if (b.squares[63] != Piece.make(BLACK, ROOK)) b.castling = b.castling and 4.inv()
            if (b.squares[56] != Piece.make(BLACK, ROOK)) b.castling = b.castling and 8.inv()
            b.key = b.computeKey()
            require(!b.isAttacked(b.kingSq[b.side xor 1], b.side)) { "Side not to move is in check" }
            return b
        }
    }
}

enum class GameStatus(val label: String) {
    ONGOING(""),
    CHECKMATE("Checkmate"),
    STALEMATE("Stalemate"),
    FIFTY_MOVES("Draw · 50 moves"),
    THREEFOLD("Draw · repetition"),
    INSUFFICIENT("Draw · material"),
}
