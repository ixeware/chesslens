package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Piece
import com.chesslens.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

/** Reading a position off the board when the game doesn't start from the initial position. */
class ScanTest {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    private val italian = "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQ1RK1 w - - 0 6"
    private val frontal = BoardQuad(listOf(Pt(140f, 40f), Pt(500f, 40f), Pt(500f, 400f), Pt(140f, 400f)))

    private fun scanWith(surface: BoardSurface, render: (Int) -> Mat): Pair<BoardScan, VisionPipeline> {
        val pipeline = VisionPipeline().also { it.setSurface(surface) }
        val start = Board.startPosition()
        var seed = 1
        repeat(8) { val img = render(seed++); pipeline.process(img, start, autoOrientation = true); img.release() }
        assertEquals(TrackingPhase.LOCKED, pipeline.phase)
        pipeline.requestScan()
        var scan: BoardScan? = null
        var frames = 0
        while (scan == null && frames < 20) {
            val img = render(seed++)
            scan = pipeline.process(img, start, autoOrientation = false).scan
            img.release()
            frames++
        }
        assertNotNull("no scan after $frames frames", scan)
        return scan!! to pipeline
    }

    private fun check(scan: BoardScan, fen: String) {
        val r = ScanInterpreter.interpret(scan, emptyList(), Board.startPosition())
        val truth = Board.fromFen(fen)
        val wrong = (0 until 64).filter { r.guess.squares[it] != truth.squares[it] }
        println("scan → ${r.guess.fen()} orientation=${r.orientation} wrong=${wrong.map { Square.name(it) }} unsure=${r.guess.uncertain.size} ${scan.note}")
        assertTrue("wrong squares: ${wrong.map { Square.name(it) }}", wrong.isEmpty())
        assertEquals(Piece.WHITE, r.guess.side)
    }

    @Test fun scansMiddlegameOnScreen() {
        val b = Board.fromFen(italian)
        val (scan, p) = scanWith(BoardSurface.SCREEN) { seed -> SyntheticScreen.render(b, null, seed) }
        check(scan, italian)
        p.release()
    }

    @Test fun scansMiddlegameOnPhysicalBoard() {
        val b = Board.fromFen(italian)
        val (scan, p) = scanWith(BoardSurface.PHYSICAL) { seed -> SyntheticBoard.render(b, frontal, seed = seed) }
        check(scan, italian)
        p.release()
    }

    @Test fun scanWorksOutWhichSideWhiteSitsOn() {
        val b = Board.fromFen(italian)
        val (scan, p) = scanWith(BoardSurface.PHYSICAL) { seed -> SyntheticBoard.render(b, frontal, orientation = 2, seed = seed) }
        val r = ScanInterpreter.interpret(scan, emptyList(), Board.startPosition())
        assertEquals(Board.fromFen(italian).placementFen(), r.guess.fen().substringBefore(' '))
        assertEquals(2, r.orientation)
        p.release()
    }
}
