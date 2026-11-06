package com.chesslens.chess

import com.chesslens.chess.Piece.BISHOP
import com.chesslens.chess.Piece.BLACK
import com.chesslens.chess.Piece.KING
import com.chesslens.chess.Piece.KNIGHT
import com.chesslens.chess.Piece.PAWN
import com.chesslens.chess.Piece.QUEEN
import com.chesslens.chess.Piece.ROOK
import com.chesslens.chess.Piece.WHITE
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns what a camera can see — which squares are occupied and by which colour — into a full
 * position. Piece types come from chess itself: either the picture matches a position the game
 * already knows (or reaches within a couple of moves), or the pieces of a reference position
 * (normally the start) are assigned to the occupied squares in the most plausible way: pieces
 * prefer to stay home, pawns only go forward, bishops keep their square colour, and so on.
 */
object PositionGuesser {

    /**
     * Per-square evidence, a1 = 0. [occupancy] > 0 means a piece (larger = surer), < 0 empty,
     * 0 unknown. [color] > 0 means a white piece, < 0 black.
     */
    class Evidence(val occupancy: DoubleArray, val color: DoubleArray)

    class Guess(
        /** Piece codes, a1 = 0. */
        val squares: IntArray,
        /** Squares the user should double-check. */
        val uncertain: Set<Int>,
        /** Likely side to move (WHITE/BLACK). */
        val side: Int,
        /** Castling rights bit mask as in [Board.castling]. */
        val castling: Int,
        /** True when the picture matched a known position exactly (types and side are known). */
        val exact: Boolean,
        val cost: Double,
    ) {
        fun fen(side: Int = this.side): String = PositionGuesser.fen(squares, side, castling)
    }

    /**
     * @param known positions the board is likely to show as is (game history, current position)
     * @param current the game position, whose successors (up to [depth] plies) are tried too
     */
    fun guess(e: Evidence, known: List<Board>, current: Board?, depth: Int = 2): Guess {
        // 1. A position we already know (or are a move or two away from). Successors are scored
        // in place: copying a board copies its whole undo history.
        var best: Board? = null
        var bestCost = Double.MAX_VALUE
        var bestWorst = 0.0
        fun consider(c: Board, keep: () -> Board) {
            var total = 0.0
            var worst = 0.0
            for (sq in 0 until 64) {
                val k = squareCost(e, sq, c.squares[sq])
                total += k
                worst = max(worst, k)
                if (total > bestCost) return
            }
            bestCost = total; best = keep(); bestWorst = worst
        }
        for (k in known) consider(k) { k }
        val start = Board.startPosition()
        consider(start) { start }
        if (current != null) {
            val b = current.copy()
            fun walk(d: Int) {
                if (d == 0) return
                for (m in b.legalMoves()) {
                    b.makeMove(m)
                    consider(b) { b.copy() }
                    walk(d - 1)
                    b.unmakeMove()
                }
            }
            walk(depth)
        }
        val found = best
        if (found != null && bestCost <= EXACT_TOTAL && bestWorst <= EXACT_WORST) {
            return Guess(found.squares.copyOf(), emptySet(), found.side, found.castling, exact = true, cost = bestCost)
        }
        // 2. Rebuild from a reference position by assigning its pieces to the evidence.
        val bases = listOfNotNull(Board.startPosition(), best, current).distinctBy { it.placementFen() }
        return bases.map { assign(e, it) }.minBy { it.cost }
    }

    /** Evidence weaker than this (either way) is shown to the user as unsure. */
    const val UNSURE = 1.1

    private const val EXACT_TOTAL = 3.0
    private const val EXACT_WORST = 1.6

    /** Cost of [piece] (or empty) standing on [sq] given the evidence. */
    fun squareCost(e: Evidence, sq: Int, piece: Int): Double {
        val occ = e.occupancy[sq]
        if (piece == 0) return max(0.0, occ)
        val sign = if (Piece.color(piece) == WHITE) 1.0 else -1.0
        val colour = if (occ > 0) (max(0.0, -e.color[sq] * sign) * 0.6).coerceAtMost(2.4) else 0.0
        return max(0.0, -occ) + colour
    }

    // ------------------------------------------------------------------ assignment

    private const val INF = 1e6
    private const val EXTRA = 4.5

    private fun assign(e: Evidence, base: Board): Guess {
        val pieces = (0 until 64).filter { base.squares[it] != 0 }
        val extrasPerColor = 6
        val nP = pieces.size
        val nX = extrasPerColor * 2
        val rows = nP + nX + 64
        val cols = 64 + nP + nX
        require(rows == cols)
        val n = rows
        val cost = Array(n) { DoubleArray(n) { INF } }
        // Real pieces.
        for ((i, from) in pieces.withIndex()) {
            val p = base.squares[from]
            for (to in 0 until 64) {
                val mc = moveCost(p, from, to)
                if (mc < INF) cost[i][to] = mc + squareCost(e, to, p)
            }
            val cap = captureCost(Piece.type(p))
            for (k in 64 until n) cost[i][k] = cap
        }
        // Extra pieces of unknown type (promotions, or pieces the reference lacks).
        for (x in 0 until nX) {
            val r = nP + x
            val color = if (x < extrasPerColor) WHITE else BLACK
            for (to in 0 until 64) cost[r][to] = EXTRA + squareCost(e, to, Piece.make(color, PAWN))
            for (k in 64 until n) cost[r][k] = 0.0
        }
        // Fillers: square left empty.
        for (sq in 0 until 64) {
            val r = nP + nX + sq
            cost[r][sq] = squareCost(e, sq, 0)
            for (k in 64 until n) cost[r][k] = 0.0
        }
        val match = Hungarian.solve(cost)
        val out = IntArray(64)
        val uncertain = HashSet<Int>()
        var total = 0.0
        var whiteMoves = 0.0
        var blackMoves = 0.0
        val moved = HashSet<Int>()
        // Where each real piece ended up (-1 = captured).
        val dest = IntArray(nP) { -1 }
        for (r in 0 until nP) if (match[r] < 64) dest[r] = match[r]
        for (r in 0 until n) {
            val c = match[r]
            total += cost[r][c]
            if (c >= 64) continue
            when {
                r < nP -> {
                    val from = pieces[r]
                    val p = base.squares[from]
                    out[c] = p
                    if (from != c) {
                        moved += from
                        val mc = moveCost(p, from, c)
                        if (Piece.color(p) == WHITE) whiteMoves += mc else blackMoves += mc
                        if (mc >= 1.5 || ambiguous(r, pieces, base, dest, e)) uncertain += c
                    }
                }
                r < nP + nX -> {
                    val color = if (r - nP < extrasPerColor) WHITE else BLACK
                    val rank = Square.rank(c)
                    out[c] = Piece.make(color, if (rank == 0 || rank == 7) QUEEN else PAWN)
                    uncertain += c
                }
            }
        }
        for (sq in 0 until 64) if (abs(e.occupancy[sq]) < UNSURE) uncertain += sq
        // Captured pieces count as moves for the side that took them (roughly).
        for (from in pieces) {
            val p = base.squares[from]
            if (from in moved || out[from] == p) continue
            if (Piece.color(p) == WHITE) blackMoves += 1.0 else whiteMoves += 1.0
        }
        var castling = base.castling
        fun keep(bit: Int, king: Int, rook: Int, kp: Int, rp: Int) {
            if (out[king] != kp || out[rook] != rp || king in moved || rook in moved) castling = castling and bit.inv()
        }
        keep(1, 4, 7, Piece.make(WHITE, KING), Piece.make(WHITE, ROOK))
        keep(2, 4, 0, Piece.make(WHITE, KING), Piece.make(WHITE, ROOK))
        keep(4, 60, 63, Piece.make(BLACK, KING), Piece.make(BLACK, ROOK))
        keep(8, 60, 56, Piece.make(BLACK, KING), Piece.make(BLACK, ROOK))
        var side = if (whiteMoves > blackMoves + 0.5) BLACK else WHITE
        // The side that just moved can't be in check.
        if (inCheck(out, side xor 1)) side = side xor 1
        return Guess(out, uncertain, side, castling, exact = false, cost = total)
    }

    /**
     * Could another piece of the same colour but a different type stand on piece [r]'s square
     * about as plausibly (swapping places, or that piece having been captured instead)?
     */
    private fun ambiguous(r: Int, pieces: List<Int>, base: Board, dest: IntArray, e: Evidence): Boolean {
        val p = base.squares[pieces[r]]
        val b = dest[r]
        fun placeCost(piece: Int, from: Int, to: Int): Double =
            if (to < 0) captureCost(Piece.type(piece)) else moveCost(piece, from, to) + squareCost(e, to, piece)
        val now = placeCost(p, pieces[r], b)
        for ((i, from) in pieces.withIndex()) {
            if (i == r) continue
            val q = base.squares[from]
            if (Piece.color(q) != Piece.color(p) || Piece.type(q) == Piece.type(p) || Piece.type(q) == KING) continue
            val swapped = placeCost(q, from, b) + placeCost(p, pieces[r], dest[i])
            if (swapped < INF && swapped <= now + placeCost(q, from, dest[i]) + 0.5) return true
        }
        return false
    }

    private fun captureCost(type: Int): Double = when (type) {
        PAWN -> 2.2
        KNIGHT, BISHOP -> 2.6
        ROOK -> 2.8
        QUEEN -> 3.0
        else -> INF
    }

    /** Rough number of moves for [piece] to get from [from] to [to] (INF if impossible). */
    fun moveCost(piece: Int, from: Int, to: Int): Double {
        if (from == to) return 0.0
        val type = Piece.type(piece)
        val color = Piece.color(piece)
        val df = abs(Square.file(to) - Square.file(from))
        val drSigned = Square.rank(to) - Square.rank(from)
        val dr = abs(drSigned)
        return when (type) {
            PAWN -> {
                val fwd = if (color == WHITE) drSigned else -drSigned
                val toRank = Square.rank(to)
                if (fwd < 1 || df > fwd || toRank == 0 || toRank == 7) INF
                else {
                    val startRank = if (color == WHITE) 1 else 6
                    val free = if (Square.rank(from) == startRank) 2 else 1
                    0.9 + 0.8 * df + 0.35 * max(0, fwd - df - free)
                }
            }
            KNIGHT -> 0.9 * knightDistance(from, to)
            BISHOP -> if (Square.isLight(from) != Square.isLight(to)) INF else if (df == dr) 1.0 else 1.8
            ROOK -> {
                // h1→f1 / a1→d1 come for free with castling (the king pays for the move).
                val homeRank = if (color == WHITE) 0 else 7
                val castleRook = Square.rank(from) == homeRank && Square.rank(to) == homeRank &&
                    ((Square.file(from) == 7 && Square.file(to) == 5) || (Square.file(from) == 0 && Square.file(to) == 3))
                if (castleRook) 0.4 else if (df == 0 || dr == 0) 1.0 else 1.8
            }
            QUEEN -> if (df == 0 || dr == 0 || df == dr) 1.0 else 1.8
            KING -> {
                val homeRank = if (color == WHITE) 0 else 7
                // Kings rarely walk before the endgame; castling is the usual way they move.
                if (Square.rank(from) == homeRank && Square.file(from) == 4 && Square.rank(to) == homeRank && (Square.file(to) == 6 || Square.file(to) == 2)) 0.6
                else 1.2 + 0.8 * max(df, dr)
            }
            else -> INF
        }
    }

    private val knightDist: Array<IntArray> by lazy {
        Array(64) { s ->
            val d = IntArray(64) { -1 }
            d[s] = 0
            val q = ArrayDeque<Int>()
            q += s
            while (q.isNotEmpty()) {
                val x = q.removeFirst()
                val f = Square.file(x)
                val r = Square.rank(x)
                for ((a, b) in KNIGHT_STEPS) {
                    val nf = f + a
                    val nr = r + b
                    if (nf !in 0..7 || nr !in 0..7) continue
                    val y = nr * 8 + nf
                    if (d[y] < 0) { d[y] = d[x] + 1; q += y }
                }
            }
            d
        }
    }
    private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)

    private fun knightDistance(a: Int, b: Int): Int = knightDist[a][b]

    private fun inCheck(squares: IntArray, color: Int): Boolean {
        val k = squares.indexOfFirst { it == Piece.make(color, KING) }
        if (k < 0) return false
        return Board.attackedOn(squares, k, color xor 1)
    }

    fun fen(squares: IntArray, side: Int, castling: Int): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = squares[r * 8 + f]
                if (p == 0) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                sb.append(Piece.fenChar(p))
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
        sb.append(" - 0 1")
        return sb.toString()
    }

    /** Why a position can't be played, or null if it can. */
    fun problem(squares: IntArray, side: Int): String? {
        val wk = squares.count { it == Piece.make(WHITE, KING) }
        val bk = squares.count { it == Piece.make(BLACK, KING) }
        if (wk != 1) return if (wk == 0) "White has no king" else "White has more than one king"
        if (bk != 1) return if (bk == 0) "Black has no king" else "Black has more than one king"
        for (sq in 0 until 64) {
            val p = squares[sq]
            if (Piece.type(p) == PAWN && (Square.rank(sq) == 0 || Square.rank(sq) == 7)) return "Pawn on ${Square.name(sq)} can't stand on the first or last rank"
        }
        if (squares.count { it != 0 && Piece.color(it) == WHITE } > 16) return "White has more than 16 pieces"
        if (squares.count { it != 0 && Piece.color(it) == BLACK } > 16) return "Black has more than 16 pieces"
        if (inCheck(squares, side xor 1)) return "${if (side == WHITE) "Black" else "White"} is in check but it's ${if (side == WHITE) "White" else "Black"}'s move"
        return null
    }
}

/** Minimum-cost perfect matching on a square cost matrix (Kuhn–Munkres, O(n³)). */
internal object Hungarian {
    /** Returns, for each row, the assigned column. */
    fun solve(a: Array<DoubleArray>): IntArray {
        val n = a.size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.MAX_VALUE }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..n) {
                    if (used[j]) continue
                    val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..n) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val ans = IntArray(n)
        for (j in 1..n) if (p[j] != 0) ans[p[j] - 1] = j - 1
        return ans
    }
}
