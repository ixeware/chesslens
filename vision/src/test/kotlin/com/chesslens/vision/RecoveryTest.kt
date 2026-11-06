package com.chesslens.vision

import com.chesslens.chess.Board
import com.chesslens.chess.Move
import com.chesslens.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Mat

/** The pipeline notices when the board and the app disagree, and says when a move is illegal. */
class RecoveryTest {
    init {
        nu.pattern.OpenCV.loadLocally()
    }

    private val frontal = BoardQuad(listOf(Pt(140f, 40f), Pt(500f, 40f), Pt(500f, 400f), Pt(140f, 400f)))

    /** Feeds frames; applies catch-ups to the position like the app does (a new board object). */
    private fun run(pipeline: VisionPipeline, frames: Int, start: Board, render: (Int) -> Mat, seed0: Int = 1): List<VisionEvent> {
        val events = ArrayList<VisionEvent>()
        var board = start
        for (i in 0 until frames) {
            val img = render(seed0 + i)
            val e = pipeline.process(img, board, autoOrientation = false).event
            img.release()
            if (e != null) events += e
            if (e is VisionEvent.CaughtUp) {
                board = board.copy().apply {
                    repeat(e.undo) { unmakeMove() }
                    e.moves.forEach { makeMove(it) }
                }
            }
        }
        return events
    }

    /** The board is a move ahead of the app (a missed move): the periodic check plays it. */
    @Test fun catchesUpOnAMissedMove() {
        val app = Board.fromFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val shown = app.copy().apply { makeMove(parseUci("f1b5")!!) }
        val pipeline = VisionPipeline()
        pipeline.setOrientation(0)
        val events = run(pipeline, 80, app, { s -> SyntheticBoard.render(shown, frontal, seed = s) })
        val caught = events.filterIsInstance<VisionEvent.CaughtUp>()
        println("events: $events")
        assertEquals(1, caught.size)
        assertEquals(0, caught[0].undo)
        assertEquals(listOf("f1b5"), caught[0].moves.map { Move.uci(it) })
        pipeline.release()
    }

    /** The last move was misread: the check takes it back and plays the right one. */
    @Test fun fixesAMisreadMove() {
        val before = Board.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2")
        val app = before.copy().apply { makeMove(parseUci("g1h3")!!) } // what the app thinks
        val shown = before.copy().apply { makeMove(parseUci("g1f3")!!) } // what was played
        val pipeline = VisionPipeline()
        pipeline.setOrientation(0)
        val events = run(pipeline, 80, app, { s -> SyntheticBoard.render(shown, frontal, seed = s) })
        val caught = events.filterIsInstance<VisionEvent.CaughtUp>()
        println("events: $events")
        assertEquals(1, caught.size)
        assertEquals(1, caught[0].undo)
        assertEquals(listOf("g1f3"), caught[0].moves.map { Move.uci(it) })
        pipeline.release()
    }

    /** When board and app agree nothing is reported, however long the camera watches. */
    @Test fun staysQuietWhenBoardMatches() {
        val app = Board.fromFen("r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQ1RK1 w - - 6 6")
        val pipeline = VisionPipeline()
        pipeline.setOrientation(0)
        val events = run(pipeline, 150, app, { s -> SyntheticBoard.render(app, frontal, seed = s) })
        println("events: $events")
        assertTrue("unexpected: $events", events.none { it is VisionEvent.CaughtUp || it is VisionEvent.PositionMismatch })
        pipeline.release()
    }

    /** White moves when it's Black's turn: reported as an illegal move, not just unreadable. */
    @Test fun reportsMoveOutOfTurn() {
        val app = Board.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        val shown = Board.fromFen("rnbqkbnr/pppppppp/8/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq - 0 1")
        val pipeline = VisionPipeline()
        pipeline.setOrientation(0)
        pipeline.positionChecks = false
        val settle = run(pipeline, 12, app, { s -> SyntheticBoard.render(app, frontal, seed = s) })
        assertTrue(settle.none { it is VisionEvent.MoveDetected })
        val hand = run(pipeline, 3, app, { s -> SyntheticBoard.render(app, frontal, seed = s, hand = true) }, 100)
        val after = run(pipeline, 10, app, { s -> SyntheticBoard.render(shown, frontal, seed = s) }, 200)
        val events = settle + hand + after
        println("events: $events")
        val illegal = events.filterIsInstance<VisionEvent.IllegalMove>()
        assertEquals(1, illegal.size)
        assertEquals("d2", Square.name(illegal[0].from))
        assertEquals("d4", Square.name(illegal[0].to))
        pipeline.release()
    }
}
