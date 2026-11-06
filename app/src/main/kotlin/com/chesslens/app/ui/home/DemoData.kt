package com.chesslens.app.ui.home

import androidx.compose.ui.geometry.Offset
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.PositionSnapshot
import com.chesslens.app.game.Settings
import com.chesslens.app.game.UiState
import com.chesslens.app.game.VisionUi
import com.chesslens.chess.Board
import com.chesslens.chess.EngineInfo
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Game
import com.chesslens.chess.MoveQuality
import com.chesslens.chess.SearchLimits
import com.chesslens.chess.Search
import com.chesslens.vision.BoardSurface
import com.chesslens.vision.TrackingPhase

/** Canned positions for the home hero, the no-camera demo and screenshot tests. */
object DemoData {
    /** Sicilian Najdorf-ish game; Black to move. */
    val sicilian = listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3")

    fun game(ucis: List<String>, startFen: String = Board.START_FEN): Game {
        val g = Game(startFen)
        for (u in ucis) {
            val m = g.current.parseUci(u) ?: error("illegal demo move $u")
            g.play(m)
        }
        return g
    }

    /** Builds an engine result from (pv in UCI, centipawns from side to move). */
    fun info(board: Board, lines: List<Pair<String, Int>>, depth: Int = 22): EngineInfo {
        val out = lines.map { (pv, cp) ->
            val b = board.copy()
            val moves = pv.split(' ').map { u -> b.parseUci(u)!!.also { b.makeMove(it) } }
            EngineLine(moves, cp, null)
        }
        return EngineInfo(depth, depth + 6, 4_200_000, 2400, out, board.side, board.toFen())
    }

    fun state(
        game: Game,
        info: EngineInfo?,
        mode: OverlayMode = OverlayMode.HINT,
        playAs: PlayAs = PlayAs.BOTH,
        threat: EngineLine? = null,
        qualities: Map<Int, MoveQuality> = emptyMap(),
        heat: Boolean = false,
        surface: BoardSurface = BoardSurface.PHYSICAL,
    ): UiState {
        val b = game.current
        return UiState(
            moves = game.moves.toList(),
            viewPly = game.moves.size,
            position = PositionSnapshot.of(b, game.moves.lastOrNull()?.san),
            analysis = info,
            searching = false,
            threat = threat,
            qualities = qualities,
            evalByPly = List(game.moves.size + 1) { i -> listOf(20, 35, 28, 40, 31, 45, 38, 30, 34, 26, 40, 55, 30)[i % 13] * (if (i % 4 == 0) -1 else 1) },
            accuracyWhite = if (qualities.isNotEmpty()) 91.4 else null,
            accuracyBlack = if (qualities.isNotEmpty()) 84.7 else null,
            settings = Settings(playAs = playAs, overlayMode = mode, showHeatmap = heat, voice = true, surface = surface),
        )
    }

    fun vision(quad: List<Offset>, orientation: Int, hand: Boolean = false): VisionUi =
        VisionUi(quad, TrackingPhase.LOCKED, orientation, hand, 29f, 18, null)

    /** Home hero: Black to move after 5.Nc3, engine suggests 5...a6 with alternatives. */
    fun heroState(mode: OverlayMode = OverlayMode.HINT, surface: BoardSurface = BoardSurface.PHYSICAL): UiState {
        val g = game(listOf("e2e4", "e7e5", "g1f3"))
        val info = info(g.current, listOf("b8c6 f1b5 a7a6" to -30, "g8f6 f3e5 d7d6" to -38, "d7d6 d2d4 e5d4" to -52))
        return state(g, info, mode, surface = surface)
    }

    /** Quick sanity helper used by tests: real engine lines for a game. */
    fun analysed(game: Game, multiPv: Int = 3, depth: Int = 8): EngineInfo =
        Search(18).run(game.current, SearchLimits(maxDepth = depth, multiPv = multiPv))
}
