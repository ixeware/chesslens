package com.chesslens.app.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.automirrored.rounded.LastPage
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chesslens.app.game.GameViewModel
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.ar.OverlayModel
import com.chesslens.app.ui.board.BoardArrow
import com.chesslens.app.ui.board.ChessBoard
import com.chesslens.app.ui.camera.AccuracyRow
import com.chesslens.app.ui.components.EngineChip
import com.chesslens.app.ui.components.EvalBar
import com.chesslens.app.ui.components.EvalGraph
import com.chesslens.app.ui.components.QualityIcon
import com.chesslens.app.ui.components.RoundIconButton
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Board
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Move
import com.chesslens.chess.Piece
import com.chesslens.chess.San

data class AnalysisActions(
    val onBack: () -> Unit = {},
    val onCamera: () -> Unit = {},
    val onGoTo: (Int) -> Unit = {},
    val onMove: (Int, Int) -> Unit = { _, _ -> },
)

@Composable
fun AnalysisScreen(vm: GameViewModel, onBack: () -> Unit, onCamera: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.setDeepAnalysis(true)
        onDispose { vm.setDeepAnalysis(false) }
    }
    AnalysisContent(state, AnalysisActions(onBack, onCamera, vm::goToPly, vm::playFromTo))
}

@Composable
fun AnalysisContent(state: UiState, actions: AnalysisActions) {
    var flipped by remember { mutableStateOf(state.settings.playAs == PlayAs.BLACK) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Lens.Background)) {
        val landscape = maxWidth > maxHeight
        val boxW = maxWidth
        val boxH = maxHeight
        val boardBlock: @Composable (Dp) -> Unit = { size -> BoardWithBar(state, flipped, actions, size) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(state, onBack = actions.onBack, onFlip = { flipped = !flipped }, onCamera = actions.onCamera)
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    boardBlock(boxH - 90.dp)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NavRow(state, actions)
                        EngineLines(state)
                        EvalGraph(state.evalByPly, state.viewPly, state.qualities) { actions.onGoTo(it) }
                        AccuracyRow(state)
                        MoveList(state, actions)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    boardBlock(boxW - 24.dp)
                    NavRow(state, actions)
                    EngineLines(state)
                    EvalGraph(state.evalByPly, state.viewPly, state.qualities) { actions.onGoTo(it) }
                    AccuracyRow(state)
                    MoveList(state, actions)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, onBack: () -> Unit, onFlip: () -> Unit, onCamera: () -> Unit) {
    val info = state.analysis?.takeIf { it.fen == state.position.fen }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 520.dp
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            if (wide) Text("Analysis", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            EngineChip(info?.depth ?: 0, info?.let { OverlayModel.evalText(it, it.best) } ?: "", info?.source ?: "Local", state.searching)
            RoundIconButton(Icons.Rounded.SwapVert, "Flip", onClick = onFlip)
            RoundIconButton(Icons.Rounded.CameraAlt, "Camera", onClick = onCamera)
        }
    }
}

@Composable
private fun BoardWithBar(state: UiState, flipped: Boolean, actions: AnalysisActions, size: Dp) {
    val pos = state.position
    val info = state.analysis?.takeIf { it.fen == pos.fen }
    val board = remember(pos.fen) { Board.fromFen(pos.fen) }
    val arrows = buildList {
        info?.lines?.take(state.settings.multiPv)?.forEachIndexed { i, l ->
            add(BoardArrow(Move.from(l.move), Move.to(l.move), Lens.LineColors[i], if (i == 0) 0.2f else 0.13f))
        }
        if (state.settings.showThreats) state.threat?.let { t ->
            val nb = board.copy().also { it.makeNullMove() }
            if (nb.pieceAt(Move.from(t.move)) != 0) add(BoardArrow(Move.from(t.move), Move.to(t.move), Lens.Red, 0.1f))
        }
    }
    val badgePly = state.viewPly - 1
    val badge = state.qualities[badgePly]?.let { q -> Move.to(state.moves[badgePly].move) to q }
    Row(Modifier.width(size).height(size - 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EvalBar(info?.whiteCp() ?: 0, info?.whiteMate(), flipped, Modifier)
        ChessBoard(
            squares = pos.squares,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)),
            flipped = flipped,
            lastMove = pos.lastMove,
            arrows = arrows,
            checkSquare = if (pos.inCheck) pos.kingSquare else -1,
            badge = badge,
            legalMoves = pos.legalMoves,
            heat = if (state.settings.showHeatmap) pos.control else null,
            hanging = pos.hanging,
            onMove = actions.onMove,
        )
    }
}

@Composable
private fun NavRow(state: UiState, actions: AnalysisActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Rounded.FirstPage, "Start") { actions.onGoTo(0) }
        RoundIconButton(Icons.Rounded.ChevronLeft, "Back") { actions.onGoTo(state.viewPly - 1) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val label = if (state.viewPly == 0) "Start position" else {
                val m = state.moves[state.viewPly - 1]
                "${m.moveNumber}${if (m.color == Piece.WHITE) "." else "…"} ${m.san}"
            }
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        RoundIconButton(Icons.Rounded.ChevronRight, "Forward") { actions.onGoTo(state.viewPly + 1) }
        RoundIconButton(Icons.AutoMirrored.Rounded.LastPage, "End") { actions.onGoTo(state.moves.size) }
    }
}

@Composable
private fun EngineLines(state: UiState) {
    val pos = state.position
    val info = state.analysis?.takeIf { it.fen == pos.fen } ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Lens.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        info.lines.take(state.settings.multiPv).forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val text = OverlayModel.evalText(info, line)
                val whiteAhead = info.whiteCp(line) >= 0
                Box(
                    Modifier.width(58.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (whiteAhead) Lens.EvalWhite else Lens.EvalBlack)
                        .border(1.5.dp, Lens.LineColors[i], RoundedCornerShape(6.dp))
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(text, color = if (whiteAhead) Lens.EvalBlack else Lens.EvalWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Text(pvSan(pos.fen, line), color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        state.threat?.let { t ->
            if (state.settings.showThreats) {
                val nb = Board.fromFen(pos.fen).also { it.makeNullMove() }
                if (nb.pieceAt(Move.from(t.move)) != 0) {
                    Text("Threat: ${San.of(nb, t.move)}", color = Lens.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** "12. Nf3 Nc6 13. Bb5" for an engine line from [fen]. */
fun pvSan(fen: String, line: EngineLine, max: Int = 10): String {
    val b = Board.fromFen(fen)
    val sb = StringBuilder()
    for ((i, m) in line.moves.take(max).withIndex()) {
        if (m !in b.legalMoves()) break
        if (b.side == Piece.WHITE) sb.append(b.fullmoveNumber).append(". ")
        else if (i == 0) sb.append(b.fullmoveNumber).append("… ")
        sb.append(San.of(b, m)).append(' ')
        b.makeMove(m)
    }
    return sb.toString().trim()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveList(state: UiState, actions: AnalysisActions) {
    if (state.moves.isEmpty()) {
        Text("Play moves on the board, or use the camera to record a game.", color = Lens.TextMuted, fontSize = 13.sp)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.moves.forEachIndexed { i, m ->
            val selected = state.viewPly == i + 1
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Lens.Neon.copy(alpha = 0.25f) else Lens.Surface)
                    .clickable { actions.onGoTo(i + 1) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (m.color == Piece.WHITE) Text("${m.moveNumber}.", color = Lens.TextMuted, fontSize = 12.sp)
                Text(m.san, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                state.qualities[i]?.let { QualityIcon(it, 15.dp) }
            }
        }
    }
}
