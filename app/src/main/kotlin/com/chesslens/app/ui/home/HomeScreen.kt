package com.chesslens.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chesslens.app.game.GameViewModel
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.Settings
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.ar.ArOverlay
import com.chesslens.app.ui.ar.OverlayFx
import com.chesslens.app.ui.board.BoardArrow
import com.chesslens.app.ui.board.ChessBoard
import com.chesslens.app.ui.camera.DemoBackdrop
import com.chesslens.app.ui.camera.DemoScreenBackdrop
import com.chesslens.app.ui.camera.FenDialog
import com.chesslens.app.ui.camera.SettingsSheet
import com.chesslens.app.ui.camera.demoQuad
import com.chesslens.app.ui.camera.demoScreenQuad
import com.chesslens.app.ui.components.BevelIconButton
import com.chesslens.app.ui.components.EngineRing
import com.chesslens.app.ui.components.ForgeChip
import com.chesslens.app.ui.components.GlowButton
import com.chesslens.app.ui.components.SectionHeader
import com.chesslens.app.ui.components.TabBand
import com.chesslens.app.ui.components.TabItem
import com.chesslens.app.ui.components.TopPill
import com.chesslens.app.ui.components.forgeSlab
import com.chesslens.app.ui.components.forgeWell
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Board
import com.chesslens.chess.Move
import com.chesslens.vision.BoardSurface
import kotlinx.coroutines.delay

data class HomeActions(
    val onStartCamera: () -> Unit = {},
    val onAnalysis: () -> Unit = {},
    val onSettings: ((Settings) -> Settings) -> Unit = {},
    val onLoadFen: (String) -> Unit = {},
)

@Composable
fun HomeScreen(vm: GameViewModel, onStartCamera: () -> Unit, onAnalysis: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    HomeContent(state, HomeActions(onStartCamera, onAnalysis, vm::updateSettings) { vm.newGame(it) })
}

@Composable
fun HomeContent(state: UiState, actions: HomeActions, animate: Boolean = true) {
    var showSettings by remember { mutableStateOf(false) }
    var showFen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().drawBehind { drawRect(Lens.Ground) }) {
        val landscape = maxWidth > maxHeight
        val topBar: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BevelIconButton(Icons.Rounded.Tune, "Settings") { showSettings = true }
                Spacer(Modifier.weight(1f))
                TopPill { Text("ChessLens", style = MaterialTheme.typography.titleLarge, color = Lens.Text) }
                Spacer(Modifier.weight(1f))
                val depth = state.analysis?.takeIf { it.fen == state.position.fen }?.depth ?: 0
                EngineRing(depth / 24f, description = "Engine depth $depth")
            }
        }
        if (landscape) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                topBar()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Composer(state, actions, animate, Modifier.weight(1.25f).fillMaxHeight(), previewWeight = true) { showFen = true }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickTools(state, actions, onFen = { showFen = true }, onSettings = { showSettings = true })
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                topBar()
                Composer(state, actions, animate, Modifier.fillMaxWidth(), previewWeight = false) { showFen = true }
                QuickTools(state, actions, onFen = { showFen = true }, onSettings = { showSettings = true })
            }
        }
        if (showSettings) SettingsSheet(state.settings, actions.onSettings) { showSettings = false }
        if (showFen) FenDialog(onDismiss = { showFen = false }, onLoad = { actions.onLoadFen(it); showFen = false })
    }
}

/** The composer slab: pick what the camera looks at, see the effect live, pick a side, start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: UiState, actions: HomeActions, animate: Boolean, modifier: Modifier, previewWeight: Boolean, onFen: () -> Unit) {
    val settings = state.settings
    val screen = settings.surface == BoardSurface.SCREEN
    Column(modifier.forgeSlab(RoundedCornerShape(28.dp))) {
        TabBand(
            items = listOf(TabItem("3D board", Icons.Rounded.ViewInAr), TabItem("2D screen", Icons.Rounded.DesktopWindows)),
            selected = settings.surface.ordinal,
            onSelect = { i -> actions.onSettings { it.copy(surface = BoardSurface.entries[i]) } },
        )
        Column(
            Modifier.padding(16.dp).then(if (previewWeight) Modifier.weight(1f) else Modifier),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LivePreview(screen, settings.overlayMode, animate, Modifier.fillMaxWidth().then(if (previewWeight) Modifier.weight(1f) else Modifier.height(230.dp)))
            Text(
                if (screen) "Point the camera at a chess site, an app or a printed diagram. Square highlights and instant replies are read correctly."
                else "Stand the phone where it sees the whole board. A move is read as soon as your hand leaves the board.",
                style = MaterialTheme.typography.bodyLarge, color = Lens.TextDim,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in PlayAs.entries) {
                    ForgeChip(
                        label = when (p) {
                            PlayAs.WHITE -> "I play White"
                            PlayAs.BLACK -> "I play Black"
                            PlayAs.BOTH -> "Hints for both"
                        },
                        selected = settings.playAs == p,
                    ) { actions.onSettings { it.copy(playAs = p) } }
                }
                ForgeChip("Start from a position", icon = Icons.Rounded.Edit, onClick = onFen)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(Lens.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BevelIconButton(Icons.AutoMirrored.Rounded.VolumeUp, if (settings.voice) "Voice hints on" else "Voice hints off", selected = settings.voice) {
                actions.onSettings { it.copy(voice = !it.voice) }
            }
            BevelIconButton(
                Icons.Rounded.Insights,
                if (settings.overlayMode == OverlayMode.ANALYSIS) "Analysis overlay on" else "Analysis overlay off",
                selected = settings.overlayMode == OverlayMode.ANALYSIS,
            ) {
                actions.onSettings { it.copy(overlayMode = if (it.overlayMode == OverlayMode.ANALYSIS) OverlayMode.HINT else OverlayMode.ANALYSIS) }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Timer, contentDescription = "Engine think time", tint = Lens.TextMuted, modifier = Modifier.size(18.dp))
            Text(
                String.format(java.util.Locale.US, "%.1f s", settings.thinkTimeMs / 1000.0),
                style = MaterialTheme.typography.titleLarge, color = Lens.TextMuted,
            )
            GlowButton("Start", Icons.Rounded.PhotoCamera, actions.onStartCamera)
        }
    }
}

/** The AR layer running on a rendered scene, so the effect is shown rather than described. */
@Composable
private fun LivePreview(screen: Boolean, mode: OverlayMode, animate: Boolean, modifier: Modifier) {
    val demo = remember(screen, mode) { DemoData.heroState(mode, if (screen) BoardSurface.SCREEN else BoardSurface.PHYSICAL) }
    var fx by remember(screen) { mutableStateOf(OverlayFx(lockNanos = if (animate) System.nanoTime() else 0L)) }
    LaunchedEffect(animate, screen) {
        while (animate) {
            delay(7000)
            fx = OverlayFx(lockNanos = System.nanoTime())
        }
    }
    BoxWithConstraints(modifier.forgeWell(RoundedCornerShape(20.dp))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val orientation = if (screen) 0 else 2
        val quad = remember(w, h, screen) { if (screen) demoScreenQuad(w, h) else demoQuad(w, h) }
        if (screen) DemoScreenBackdrop(demo.position.squares, orientation) else DemoBackdrop(demo.position.squares, orientation)
        ArOverlay(DemoData.vision(quad, orientation), demo, fx, Modifier.fillMaxSize())
    }
}

@Composable
private fun QuickTools(state: UiState, actions: HomeActions, onFen: () -> Unit, onSettings: () -> Unit) {
    SectionHeader("Quick tools", action = "Settings", onAction = onSettings)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val pos = state.position
        val best = state.analysis?.takeIf { it.fen == pos.fen }?.best
        ToolCard(
            title = "Analysis board",
            subtitle = if (state.moves.isEmpty()) "Engine lines, eval graph, accuracy" else "${state.moves.size} moves recorded",
            modifier = Modifier.weight(1f),
            onClick = actions.onAnalysis,
        ) {
            ChessBoard(
                pos.squares, Modifier.fillMaxWidth(), lastMove = pos.lastMove, showCoordinates = false,
                arrows = listOfNotNull(best?.let { BoardArrow(Move.from(it.move), Move.to(it.move), Lens.Neon, 0.2f) }),
            )
        }
        val sample = remember { Board.fromFen("r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5") }
        ToolCard(
            title = "Start from a position",
            subtitle = "Paste a FEN, then keep playing on the board",
            modifier = Modifier.weight(1f),
            onClick = onFen,
        ) {
            ChessBoard(sample.squares, Modifier.fillMaxWidth(), showCoordinates = false)
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit, thumbnail: @Composable () -> Unit) {
    Column(
        modifier
            .forgeSlab(RoundedCornerShape(24.dp), elevation = 10.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).forgeWell(RoundedCornerShape(16.dp)).padding(6.dp).clip(RoundedCornerShape(10.dp))) { thumbnail() }
        Column(Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Lens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lens.TextMuted, maxLines = 2)
        }
    }
}
