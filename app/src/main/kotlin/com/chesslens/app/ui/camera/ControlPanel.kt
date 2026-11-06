package com.chesslens.app.ui.camera

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.ar.OverlayModel
import com.chesslens.app.ui.board.BoardArrow
import com.chesslens.app.ui.board.ChessBoard
import com.chesslens.app.ui.components.PanelButton
import com.chesslens.app.ui.components.StatusDot
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Board
import com.chesslens.chess.GameStatus
import com.chesslens.chess.Move
import com.chesslens.chess.Piece
import com.chesslens.chess.San

/** Big move card: "Black to move · move 6 · last Qxd2" / "Ne4" / "Knight to E4". */
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
    val shimmer = rememberInfiniteTransition(label = "s").animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "x")

    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Lens.GlassBorder, RoundedCornerShape(16.dp))
            .padding(if (compact) 10.dp else 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(Lens.Neon))
            if (state.searching) {
                Box(
                    Modifier.fillMaxWidth((shimmer.value * 0.98f).coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f * (1 - shimmer.value))),
                )
            }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(if (pos.side == Piece.WHITE) Color.White else Color(0xFF8A938F), pulse = false)
            Text(
                "$sideName to move · move ${pos.moveNumber}$lastText",
                color = Lens.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            pos.status != GameStatus.ONGOING -> {
                Text(pos.status.label, color = Color.White, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold)
                Text("Game over", color = Lens.TextSecondary, fontSize = 12.sp)
            }
            !myTurn -> {
                Text("Opponent's turn", color = Color.White, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
                Text("Hint appears after their move", color = Lens.TextSecondary, fontSize = 12.sp)
            }
            best != null -> {
                val move = best.move
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(San.of(board, move), color = Lens.Neon, fontSize = if (compact) 26.sp else 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(OverlayModel.evalText(info, best), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Row {
                    Text(San.caption(board, move), color = Lens.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${info.source.lowercase()} · depth ${info.depth}", color = Lens.TextMuted, fontSize = 11.sp)
                }
            }
            else -> {
                Text("Thinking…", color = Color.White, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
                Text("Searching the best move", color = Lens.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

/** Mini 2D board mirroring the live game, with tap-to-move for corrections. */
@Composable
fun MiniBoard(state: UiState, onMove: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val pos = state.position
    val info = state.analysis?.takeIf { it.fen == pos.fen }
    val showBest = state.settings.isMyTurn(pos.side) || state.settings.overlayMode == OverlayMode.ANALYSIS
    val arrows = if (showBest) info?.lines.orEmpty().take(if (state.settings.overlayMode == OverlayMode.ANALYSIS) state.settings.multiPv else 1)
        .mapIndexed { i, l -> BoardArrow(Move.from(l.move), Move.to(l.move), Lens.LineColors[i], if (i == 0) 0.2f else 0.13f) } else emptyList()
    ChessBoard(
        squares = pos.squares,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        flipped = state.settings.playAs == PlayAs.BLACK,
        lastMove = pos.lastMove,
        arrows = arrows,
        checkSquare = if (pos.inCheck) pos.kingSquare else -1,
        legalMoves = pos.legalMoves,
        showCoordinates = false,
        onMove = onMove,
    )
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

/** Hints | Analysis segmented switch. */
@Composable
fun ModeSwitch(mode: OverlayMode, onChange: (OverlayMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Lens.GlassBorder, CircleShape)
            .padding(3.dp),
    ) {
        for (m in OverlayMode.entries) {
            val sel = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (sel) Lens.Neon else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, color = if (sel) Lens.PillText else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun AccuracyRow(state: UiState, modifier: Modifier = Modifier) {
    if (state.accuracyWhite == null && state.accuracyBlack == null) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, acc) in listOf("White" to state.accuracyWhite, "Black" to state.accuracyBlack)) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(8.dp),
            ) {
                Text(label, color = Lens.TextSecondary, fontSize = 10.sp)
                Text(acc?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "—", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
