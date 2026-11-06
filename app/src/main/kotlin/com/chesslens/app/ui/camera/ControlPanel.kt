package com.chesslens.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.ar.OverlayModel
import com.chesslens.app.ui.board.BoardArrow
import com.chesslens.app.ui.board.ChessBoard
import com.chesslens.app.ui.components.Meter
import com.chesslens.app.ui.components.PanelButton
import com.chesslens.app.ui.components.Segmented
import com.chesslens.app.ui.components.TabItem
import com.chesslens.app.ui.components.forgeSlab
import com.chesslens.app.ui.components.forgeWell
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Board
import com.chesslens.chess.GameStatus
import com.chesslens.chess.Move
import com.chesslens.chess.Piece
import com.chesslens.chess.San
import com.chesslens.vision.BoardSurface

/** The best-move card: label row, the move in large type, its eval, and engine depth. */
@Composable
fun MoveCard(state: UiState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val pos = state.position
    val info = state.analysis?.takeIf { it.fen == pos.fen }
    val best = info?.best
    val myTurn = state.settings.isMyTurn(pos.side) || state.settings.overlayMode == OverlayMode.ANALYSIS
    val board = Board.fromFen(pos.fen)
    val sideName = if (pos.side == Piece.WHITE) "White" else "Black"
    val lastText = pos.lastSan?.let { " · last $it" } ?: ""
    val progress by animateFloatAsState(((info?.depth ?: 0) / 24f).coerceIn(0f, 1f), label = "depth")
    val type = MaterialTheme.typography

    Column(modifier.forgeSlab(RoundedCornerShape(24.dp), elevation = 8.dp).padding(if (compact) 14.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.TipsAndUpdates, null, tint = Lens.TextMuted, modifier = Modifier.size(18.dp))
            Text(if (myTurn) "Best move" else "Waiting", style = type.titleLarge, color = Lens.TextMuted)
            Spacer(Modifier.weight(1f))
            if (best != null && myTurn && pos.status == GameStatus.ONGOING) {
                Text(OverlayModel.evalText(info, best), style = type.titleLarge, color = Lens.Text)
            }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        when {
            pos.status != GameStatus.ONGOING -> {
                Text(pos.status.label, style = if (compact) type.headlineMedium else type.displaySmall, color = Lens.Text)
                Text("Game over", style = type.bodyMedium, color = Lens.TextMuted)
            }
            !myTurn -> {
                Text("Opponent's turn", style = if (compact) type.headlineSmall else type.headlineMedium, color = Lens.Text)
                Text("Hint appears after their move", style = type.bodyMedium, color = Lens.TextMuted)
            }
            best != null -> {
                Text(San.of(board, best.move), style = if (compact) type.headlineLarge else type.displayMedium, color = Lens.Green)
                Text("${San.caption(board, best.move)} · ${info.source.lowercase()} depth ${info.depth}", style = type.bodyMedium, color = Lens.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Text("Thinking", style = if (compact) type.headlineSmall else type.headlineMedium, color = Lens.Text)
                Text("Searching for the best move", style = type.bodyMedium, color = Lens.TextMuted)
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
        Meter(progress)
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text("$sideName to move · move ${pos.moveNumber}$lastText", style = type.bodySmall, color = Lens.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Mini 2D board mirroring the live game; tap it to open the analysis board (and fix moves there). */
@Composable
fun MiniBoard(state: UiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val pos = state.position
    val info = state.analysis?.takeIf { it.fen == pos.fen }
    val analysis = state.settings.overlayMode == OverlayMode.ANALYSIS
    val showBest = state.settings.isMyTurn(pos.side) || analysis
    val arrows = if (showBest) info?.lines.orEmpty().take(if (analysis) state.settings.multiPv else 1)
        .mapIndexed { i, l -> BoardArrow(Move.from(l.move), Move.to(l.move), Lens.LineColors[i], if (i == 0) 0.2f else 0.13f) } else emptyList()
    Box(
        modifier
            .forgeWell(RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Open analysis board", role = Role.Button, onClick = onOpen)
            .padding(4.dp),
    ) {
        ChessBoard(
            squares = pos.squares,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            flipped = state.settings.playAs == PlayAs.BLACK,
            lastMove = pos.lastMove,
            arrows = arrows,
            checkSquare = if (pos.inCheck) pos.kingSquare else -1,
            showCoordinates = false,
        )
        // Says the board opens something bigger.
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.OpenInFull, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (state.mismatch != null) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).border(2.dp, Lens.Amber, RoundedCornerShape(10.dp)))
        }
    }
}

@Composable
fun PanelButtons(
    state: UiState,
    onToggleAr: () -> Unit,
    onToggleVoice: () -> Unit,
    onUndo: () -> Unit,
    onGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelButton(Icons.Rounded.ViewInAr, "AR", state.settings.arEnabled, Modifier.weight(1f), onToggleAr)
        PanelButton(Icons.AutoMirrored.Rounded.VolumeUp, "Voice", state.settings.voice, Modifier.weight(1f), onToggleVoice)
        PanelButton(Icons.AutoMirrored.Rounded.Undo, "Undo", false, Modifier.weight(1f), onUndo)
        PanelButton(Icons.Rounded.Apps, "Game", false, Modifier.weight(1f), onGame)
    }
}

/** Hints | Analysis. */
@Composable
fun ModeSwitch(mode: OverlayMode, onChange: (OverlayMode) -> Unit, modifier: Modifier = Modifier) {
    Segmented(
        listOf(TabItem("Hints", Icons.Rounded.Lightbulb), TabItem("Analysis", Icons.Rounded.Insights)),
        selected = mode.ordinal,
        onSelect = { onChange(OverlayMode.entries[it]) },
        modifier = modifier,
        compact = true,
    )
}

/** 3D board | 2D screen: picks the vision model for what the camera looks at. */
@Composable
fun SurfaceSwitch(surface: BoardSurface, onChange: (BoardSurface) -> Unit, modifier: Modifier = Modifier) {
    Segmented(
        listOf(TabItem("3D board", Icons.Rounded.ViewInAr), TabItem("2D screen", Icons.Rounded.DesktopWindows)),
        selected = surface.ordinal,
        onSelect = { onChange(BoardSurface.entries[it]) },
        modifier = modifier,
        compact = true,
    )
}

/** Per-side accuracy, shown once moves have been classified. */
@Composable
fun AccuracyRow(state: UiState, modifier: Modifier = Modifier) {
    if (state.accuracyWhite == null && state.accuracyBlack == null) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, acc) in listOf("White" to state.accuracyWhite, "Black" to state.accuracyBlack)) {
            Column(Modifier.weight(1f).forgeSlab(RoundedCornerShape(20.dp), elevation = 6.dp).padding(14.dp)) {
                Text("$label accuracy", style = MaterialTheme.typography.titleSmall, color = Lens.TextMuted)
                Spacer(Modifier.height(2.dp))
                Text(acc?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "—", style = MaterialTheme.typography.headlineLarge, color = Lens.Text)
                Spacer(Modifier.height(8.dp))
                Meter(((acc ?: 0.0) / 100.0).toFloat())
            }
        }
    }
}
