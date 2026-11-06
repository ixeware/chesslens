package com.chesslens.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.SetupSource
import com.chesslens.app.game.SetupState
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.analysis.AnalysisActions
import com.chesslens.app.ui.analysis.AnalysisContent
import com.chesslens.app.ui.ar.BadgeFx
import com.chesslens.app.ui.ar.LocalFxClock
import com.chesslens.app.ui.ar.OverlayFx
import com.chesslens.app.ui.camera.CameraActions
import com.chesslens.app.ui.camera.CameraScreenContent
import com.chesslens.app.ui.camera.DemoBackdrop
import com.chesslens.app.ui.home.DemoData
import com.chesslens.app.ui.home.HomeActions
import com.chesslens.app.ui.home.HomeContent
import com.chesslens.app.ui.camera.DemoScreenBackdrop
import com.chesslens.app.ui.camera.SettingsContent
import com.chesslens.vision.BoardSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import com.chesslens.app.ui.theme.Lens
import com.chesslens.app.ui.theme.ChessLensTheme
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Move
import com.chesslens.chess.MoveQuality
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens on the JVM (Robolectric native graphics) with a synthetic
 * camera scene so the AR effects can be reviewed without a device:
 * `gradle :app:recordRoborazziDebug`. Images land in app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var now = 0L
    private val clock = { now }

    private fun shoot(name: String, atSeconds: Double, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        now = 0L
        compose.setContent {
            CompositionLocalProvider(LocalFxClock provides clock) { ChessLensTheme { content() } }
        }
        compose.mainClock.advanceTimeBy(100)
        now = (atSeconds * 1e9).toLong()
        repeat(6) { compose.mainClock.advanceTimeBy(400) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun CameraScene(state: UiState, orientation: Int, fx: OverlayFx, speaking: String?, setup: SetupState? = null, backdrop: IntArray = state.position.squares) {
        var quad by remember { mutableStateOf<List<Offset>?>(null) }
        CameraScreenContent(
            state = state,
            vision = { quad?.let { DemoData.vision(it, orientation) } },
            speaking = speaking,
            fx = fx,
            torch = false,
            actions = CameraActions(),
            setup = setup,
        ) {
            if (state.settings.surface == BoardSurface.SCREEN) DemoScreenBackdrop(backdrop, orientation, onQuad = { quad = it })
            else DemoBackdrop(backdrop, orientation, onQuad = { quad = it })
        }
    }

    /** The periodic check caught a missed move, and now finds the board differing on two squares. */
    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun cameraMismatch() {
        val g = DemoData.game(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"))
        val info = DemoData.analysed(g, multiPv = 1, depth = 8)
        val state = DemoData.state(g, info, OverlayMode.HINT, PlayAs.BOTH).copy(
            banner = com.chesslens.app.game.Banner("Caught up from the board: Bb5"),
            mismatch = listOf(com.chesslens.chess.Square.parse("d7"), com.chesslens.chess.Square.parse("d6")),
        )
        shoot("camera_mismatch", 2.0) { CameraScene(state, orientation = 0, fx = OverlayFx(), speaking = null) }
    }

    /** New game mid-way: the camera has read a middlegame; two squares to check, Black to move. */
    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun setupScanned() {
        val fen = "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQ1RK1 b - - 0 5"
        val board = com.chesslens.chess.Board.fromFen(fen)
        val state = DemoData.state(DemoData.game(emptyList()), null, OverlayMode.HINT, PlayAs.BOTH)
        val setup = SetupState(
            scanning = false, progress = 1f, boardSeen = true, squares = board.squares.copyOf(), side = com.chesslens.chess.Piece.BLACK,
            castling = 0, uncertain = setOf(com.chesslens.chess.Square.parse("c4"), com.chesslens.chess.Square.parse("c5")),
            selected = com.chesslens.chess.Square.parse("c4"), orientation = 0, source = SetupSource.SCAN_GUESS,
        )
        shoot("setup_scanned", 1.3) { CameraScene(state, orientation = 0, fx = OverlayFx(), speaking = null, setup = setup, backdrop = board.squares) }
    }

    /** Scanning: the sweep crosses the board; the panel asks for a steeper view. */
    @Test
    @Config(qualifiers = "w880dp-h400dp-land-xhdpi")
    fun setupScanning() {
        val board = com.chesslens.chess.Board.fromFen("r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQ1RK1 b - - 0 5")
        val state = DemoData.state(DemoData.game(emptyList()), null, OverlayMode.HINT, PlayAs.BOTH)
        val setup = SetupState(scanning = true, progress = 0.5f, boardSeen = true, tooFlat = true, canForce = true)
        shoot("setup_scanning", 0.7) { CameraScene(state, orientation = 0, fx = OverlayFx(), speaking = null, setup = setup, backdrop = board.squares) }
    }

    /** Like the video: playing Black from the near side; opponent's last move in yellow, best move glowing. */
    @Test
    @Config(qualifiers = "w880dp-h400dp-land-xhdpi")
    fun cameraHints() {
        val g = DemoData.game(listOf("e2e4", "e7e5", "g1f3"))
        val info = DemoData.info(g.current, listOf("b8c6 f1b5 a7a6" to -30, "g8f6 f3e5 d7d6" to -38, "d7d6 d2d4 e5d4" to -52))
        val state = DemoData.state(g, info, OverlayMode.HINT, PlayAs.BLACK)
        shoot("camera_hints", 2.4) {
            CameraScene(state, orientation = 2, fx = OverlayFx(waveNanos = 1_500_000_000L, waveOrigin = Move.to(g.moves.last().move)), speaking = "Knight to C6")
        }
    }

    /** Analysis mode: three engine lines, threat arrow, hanging piece, PV numbers, flying eval bar. */
    @Test
    @Config(qualifiers = "w880dp-h400dp-land-xhdpi")
    fun cameraAnalysis() {
        val g = DemoData.game(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5"))
        val info = DemoData.analysed(g, multiPv = 3, depth = 9)
        val threatMove = g.current.copy().also { it.makeNullMove() }.parseUci("g5f7")!!
        val state = DemoData.state(g, info, OverlayMode.ANALYSIS, PlayAs.BOTH, threat = EngineLine(listOf(threatMove), 180, null))
        shoot("camera_analysis", 2.6) {
            CameraScene(state, orientation = 0, fx = OverlayFx(), speaking = null)
        }
    }

    /** Légal's mate trap: check alert on the king and a "Brilliant !!" badge bursting from the bishop. */
    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun cameraCheckPortrait() {
        val g = DemoData.game(listOf("e2e4", "e7e5", "g1f3", "d7d6", "f1c4", "c8g4", "b1c3", "g7g6", "f3e5", "g4d1", "c4f7"))
        val info = DemoData.analysed(g, multiPv = 1, depth = 6)
        val ply = g.moves.size - 1
        val state = DemoData.state(g, info, OverlayMode.HINT, PlayAs.BOTH, qualities = mapOf(ply to MoveQuality.BRILLIANT))
        shoot("camera_check_portrait", 2.2) {
            CameraScene(state, orientation = 0, fx = OverlayFx(badge = BadgeFx(Move.to(g.moves.last().move), MoveQuality.BRILLIANT, 1_750_000_000L)), speaking = null)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun home() {
        shoot("home", 2.5) { HomeContent(DemoData.heroState(), HomeActions(), animate = false) }
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun analysisBoard() {
        val g = DemoData.game(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "f3g5", "d7d5", "e4d5", "f6d5", "g5f7"))
        val info = DemoData.analysed(g, multiPv = 3, depth = 9)
        val q = mapOf(0 to MoveQuality.BOOK, 1 to MoveQuality.BOOK, 2 to MoveQuality.BOOK, 3 to MoveQuality.BOOK, 4 to MoveQuality.BEST, 5 to MoveQuality.GOOD, 6 to MoveQuality.INACCURACY, 7 to MoveQuality.BEST, 8 to MoveQuality.BEST, 9 to MoveQuality.MISTAKE, 10 to MoveQuality.BRILLIANT)
        val state = DemoData.state(g, info, OverlayMode.ANALYSIS, PlayAs.BOTH, qualities = q)
        shoot("analysis_board", 1.0) { AnalysisContent(state, AnalysisActions()) }
    }

    /** 2D mode: a chess site on a monitor; flat labels, same hints. */
    @Test
    @Config(qualifiers = "w880dp-h400dp-land-xhdpi")
    fun cameraScreen2d() {
        val g = DemoData.game(listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4"))
        val info = DemoData.analysed(g, multiPv = 3, depth = 8)
        val state = DemoData.state(g, info, OverlayMode.HINT, PlayAs.BLACK, surface = BoardSurface.SCREEN)
        shoot("camera_screen_2d", 2.4) {
            CameraScene(state, orientation = 0, fx = OverlayFx(), speaking = null)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h860dp-port-xhdpi")
    fun homeScreenTab() {
        shoot("home_2d", 2.5) {
            HomeContent(DemoData.heroState(surface = BoardSurface.SCREEN), HomeActions(), animate = false)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h1500dp-port-xhdpi")
    fun settings() {
        shoot("settings", 0.5) {
            Box(Modifier.fillMaxSize().background(Lens.Ground).statusBarsPadding()) {
                SettingsContent(DemoData.heroState().settings, {})
            }
        }
    }
}
