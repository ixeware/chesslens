package com.chesslens.chess

/**
 * Compact primitive encodings used throughout the engine.
 *
 * Squares: 0..63, a1 = 0, b1 = 1 ... h8 = 63.
 * Pieces:  0 = empty, white 1..6 (P N B R Q K), black 9..14 (colour bit = 8).
 * Moves:   Int = from | to << 6 | promoType << 12 | flags << 16.
 */
object Piece {
    const val NONE = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    const val WHITE = 0
    const val BLACK = 1

    fun make(color: Int, type: Int): Int = (color shl 3) or type
    fun type(piece: Int): Int = piece and 7
    fun color(piece: Int): Int = piece shr 3

    /** Conventional material values in centipawns (used for SEE, ordering, UI). */
    val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

    fun letter(type: Int): Char = " PNBRQK"[type]

    fun fenChar(piece: Int): Char {
        val c = " pnbrqk"[type(piece)]
        return if (color(piece) == WHITE) c.uppercaseChar() else c
    }

    fun fromFenChar(c: Char): Int {
        val type = " pnbrqk".indexOf(c.lowercaseChar())
        require(type > 0) { "Bad piece char '$c'" }
        return make(if (c.isUpperCase()) WHITE else BLACK, type)
    }

    fun name(type: Int): String = when (type) {
        PAWN -> "Pawn"
        KNIGHT -> "Knight"
        BISHOP -> "Bishop"
        ROOK -> "Rook"
        QUEEN -> "Queen"
        KING -> "King"
        else -> ""
    }
}

object Square {
    fun of(file: Int, rank: Int): Int = rank * 8 + file
    fun file(sq: Int): Int = sq and 7
    fun rank(sq: Int): Int = sq shr 3
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"
    fun parse(s: String): Int {
        require(s.length == 2) { "Bad square '$s'" }
        val f = s[0] - 'a'
        val r = s[1] - '1'
        require(f in 0..7 && r in 0..7) { "Bad square '$s'" }
        return of(f, r)
    }
    fun isLight(sq: Int): Boolean = (file(sq) + rank(sq)) % 2 == 1
}

object Move {
    const val NONE = 0

    const val F_CAPTURE = 1
    const val F_EP = 2
    const val F_CASTLE = 4
    const val F_DOUBLE = 8

    fun make(from: Int, to: Int, promo: Int = 0, flags: Int = 0): Int =
        from or (to shl 6) or (promo shl 12) or (flags shl 16)

    fun from(m: Int): Int = m and 63
    fun to(m: Int): Int = (m shr 6) and 63
    fun promo(m: Int): Int = (m shr 12) and 15
    fun flags(m: Int): Int = (m shr 16) and 15
    fun isCapture(m: Int): Boolean = flags(m) and F_CAPTURE != 0
    fun isEnPassant(m: Int): Boolean = flags(m) and F_EP != 0
    fun isCastle(m: Int): Boolean = flags(m) and F_CASTLE != 0
    fun isPromotion(m: Int): Boolean = promo(m) != 0
    fun isQuiet(m: Int): Boolean = !isCapture(m) && !isPromotion(m)

    fun uci(m: Int): String {
        if (m == NONE) return "0000"
        val p = promo(m)
        return Square.name(from(m)) + Square.name(to(m)) + (if (p != 0) " pnbrqk"[p].toString() else "")
    }
}

/** Precomputed attack/step tables. */
internal object Tables {
    private val KNIGHT_D = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
        intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
    )
    private val KING_D = arrayOf(
        intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
        intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
    )
    private val RAY_D = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    val knight: Array<IntArray> = Array(64) { sq -> steps(sq, KNIGHT_D) }
    val king: Array<IntArray> = Array(64) { sq -> steps(sq, KING_D) }

    /** pawnAttacks[color][sq] = squares a pawn of [color] standing on sq attacks. */
    val pawnAttacks: Array<Array<IntArray>> = arrayOf(
        Array(64) { sq -> steps(sq, arrayOf(intArrayOf(-1, 1), intArrayOf(1, 1))) },
        Array(64) { sq -> steps(sq, arrayOf(intArrayOf(-1, -1), intArrayOf(1, -1))) },
    )

    /** Directions 0..3 are orthogonal (rook), 4..7 diagonal (bishop). */
    val rays: Array<Array<IntArray>> = Array(8) { d ->
        Array(64) { sq ->
            val (df, dr) = RAY_D[d]
            val out = ArrayList<Int>()
            var f = Square.file(sq) + df
            var r = Square.rank(sq) + dr
            while (f in 0..7 && r in 0..7) {
                out += Square.of(f, r)
                f += df
                r += dr
            }
            out.toIntArray()
        }
    }

    private fun steps(sq: Int, deltas: Array<IntArray>): IntArray {
        val out = ArrayList<Int>()
        for ((df, dr) in deltas) {
            val f = Square.file(sq) + df
            val r = Square.rank(sq) + dr
            if (f in 0..7 && r in 0..7) out += Square.of(f, r)
        }
        return out.toIntArray()
    }
}

internal object Zobrist {
    val piece: Array<LongArray>
    val side: Long
    val castling: LongArray
    val epFile: LongArray

    init {
        var s = 0x9E3779B97F4A7C15uL.toLong()
        fun next(): Long {
            // splitmix64
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        piece = Array(15) { LongArray(64) { next() } }
        side = next()
        castling = LongArray(16) { next() }
        epFile = LongArray(8) { next() }
    }
}
