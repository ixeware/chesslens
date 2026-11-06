package com.chesslens.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionGuesserTest {

    /** Clean evidence for [fen]: strong occupancy and colour on every square. */
    private fun evidence(fen: String, strength: Double = 3.0): PositionGuesser.Evidence {
        val b = Board.fromFen(fen)
        val occ = DoubleArray(64) { if (b.squares[it] != 0) strength else -strength }
        val col = DoubleArray(64) { val p = b.squares[it]; if (p == 0) 0.0 else if (Piece.color(p) == Piece.WHITE) 2.5 else -2.5 }
        return PositionGuesser.Evidence(occ, col)
    }

    private fun placement(g: PositionGuesser.Guess) = g.fen().substringBefore(' ')

    @Test fun startPositionIsRecognisedExactly() {
        val g = PositionGuesser.guess(evidence(Board.START_FEN), emptyList(), null)
        assertTrue(g.exact)
        assertEquals(Board.START_FEN.substringBefore(' '), placement(g))
        assertEquals(Piece.WHITE, g.side)
        assertEquals(15, g.castling)
    }

    @Test fun positionTwoPliesAheadOfTheGameIsExact() {
        val game = Game()
        game.play(game.current.parseUci("e2e4")!!)
        val after = game.current.copy().apply { makeMove(parseUci("c7c5")!!); makeMove(parseUci("g1f3")!!) }
        val g = PositionGuesser.guess(evidence(after.toFen()), listOf(game.current), game.current)
        assertTrue(g.exact)
        assertEquals(after.placementFen(), placement(g))
        assertEquals(Piece.BLACK, g.side)
    }

    @Test fun openingPositionIsRebuiltFromTheStart() {
        val fen = "rnbqkbnr/pppp1p1p/8/4p1p1/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3"
        val g = PositionGuesser.guess(evidence(fen), emptyList(), null)
        assertFalse(g.exact)
        assertEquals(fen.substringBefore(' '), placement(g))
        assertEquals(Piece.WHITE, g.side)
        assertEquals(15, g.castling)
    }

    @Test fun castledMiddlegameIsRebuilt() {
        // Italian game after a few moves, both sides castled.
        val fen = "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 6"
        val g = PositionGuesser.guess(evidence(fen), emptyList(), null)
        assertEquals(fen.substringBefore(' '), placement(g))
        assertEquals(0, g.castling)
    }

    @Test fun realScanFromAHandHeldPhone() {
        // Scanner output on a real recording after 1.e4 e5 2.Nf3 g5 (a1 out of frame).
        val occRanks = listOf(
            doubleArrayOf(4.0, 4.0, 4.0, 4.0, 4.0, 4.0, -1.9, 4.0),
            doubleArrayOf(0.5, 0.8, -0.4, 0.8, 0.8, 0.8, 0.8, 0.8),
            doubleArrayOf(-0.8, -0.8, -0.8, -0.8, -0.4, 0.4, -0.8, -0.8),
            doubleArrayOf(-2.4, -2.1, -2.2, -1.2, 0.8, 0.8, -2.0, -2.2),
            doubleArrayOf(-2.1, -2.3, -2.0, -0.8, 0.5, -0.8, -0.1, -2.1),
            doubleArrayOf(-2.3, -1.6, -2.0, -1.6, -0.8, -2.2, 0.8, -2.3),
            doubleArrayOf(-0.1, 0.8, -0.4, 0.8, -1.3, 0.8, -0.8, 0.8),
            doubleArrayOf(0.8, 0.2, 0.8, 0.6, 0.8, 0.5, 0.8, 0.2),
        )
        val colRanks = listOf(
            doubleArrayOf(-0.31, 2.06, 0.66, 2.63, 4.00, 2.74, -2.86, 2.74),
            doubleArrayOf(1.64, 2.40, 1.71, 2.40, 1.71, 2.40, 1.71, 2.40),
            doubleArrayOf(-1.71, 1.71, -1.71, 1.71, -1.71, 1.71, -1.71, 1.71),
            doubleArrayOf(2.86, -2.86, 2.86, -2.86, 1.71, 0.62, 2.86, -2.86),
            doubleArrayOf(-2.86, 2.86, -2.86, 1.71, -1.71, 1.71, -1.71, 2.86),
            doubleArrayOf(2.86, -2.86, 2.86, -2.86, 1.71, -2.86, 1.42, -2.86),
            doubleArrayOf(-1.71, -2.40, -1.71, -2.40, -2.86, -2.40, -1.71, -2.40),
            doubleArrayOf(-2.40, -1.71, -2.40, -1.71, -2.40, -1.71, -2.40, -1.71),
        )
        val occ = DoubleArray(64) { occRanks[it / 8][it % 8] }
        val col = DoubleArray(64) { colRanks[it / 8][it % 8] }
        occ[0] = 0.0
        col[0] = 0.0
        val g = PositionGuesser.guess(PositionGuesser.Evidence(occ, col), emptyList(), Board.startPosition())
        val truth = Board.fromFen("rnbqkbnr/pppp1p1p/8/4p1p1/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3")
        val wrong = (0 until 64).filter { g.squares[it] != truth.squares[it] }
        println("guess ${g.fen()} wrong=${wrong.map { Square.name(it) }} uncertain=${g.uncertain.map { Square.name(it) }.sorted()}")
        // Whatever it gets wrong must be flagged for the user to check.
        assertTrue("unflagged mistakes: ${wrong.filter { it !in g.uncertain }.map { Square.name(it) }}", wrong.all { it in g.uncertain })
        assertTrue("too many mistakes: $wrong", wrong.size <= 4)
    }

    @Test fun problemsAreReported() {
        val start = Board.startPosition().squares
        assertNull(PositionGuesser.problem(start, Piece.WHITE))
        val noKing = start.copyOf().also { it[4] = 0 }
        assertEquals("White has no king", PositionGuesser.problem(noKing, Piece.WHITE))
        val pawnOnFirst = start.copyOf().also { it[1] = Piece.make(Piece.WHITE, Piece.PAWN) }
        assertTrue(PositionGuesser.problem(pawnOnFirst, Piece.WHITE)!!.contains("b1"))
    }

    @Test fun hungarianFindsTheCheapestMatching() {
        val m = arrayOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0))
        val a = Hungarian.solve(m)
        assertEquals(5.0, (0..2).sumOf { m[it][a[it]] }, 1e-9)
    }
}
