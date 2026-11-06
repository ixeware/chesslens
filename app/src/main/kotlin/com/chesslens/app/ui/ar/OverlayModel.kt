package com.chesslens.app.ui.ar

import androidx.compose.ui.graphics.Color
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.game.UiState
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Board
import com.chesslens.chess.EngineInfo
import com.chesslens.chess.EngineLine
import com.chesslens.chess.Move
import com.chesslens.chess.Piece
import com.chesslens.chess.San
import com.chesslens.chess.Tactics
import java.util.Locale

/** One engine line prepared for display. */
data class LineView(
    val line: EngineLine,
    val move: Int,
    val san: String,
    val evalText: String,
    val color: Color,
    val isKnight: Boolean,
)

/** Everything the AR layer draws, derived from [UiState]. */
data class OverlayModel(
    val squares: IntArray,
    val sideToMove: Int,
    val analysisMode: Boolean,
    val best: LineView?,
    val alts: List<LineView>,
    val showBest: Boolean,
    val lastMove: Int,
    val lastSan: String?,
    val checkSquare: Int,
    val threat: LineView?,
    val hanging: List<Int>,
    val control: IntArray?,
    val pv: List<Int>,
    val whiteWin: Float,
    val evalText: String,
    val depth: Int,
    val myTurn: Boolean,
) {
    companion object {
        fun from(state: UiState): OverlayModel {
            val pos = state.position
            val board = Board.fromFen(pos.fen)
            val info = state.analysis?.takeIf { it.fen == pos.fen }
            val analysisMode = state.settings.overlayMode == OverlayMode.ANALYSIS
            val myTurn = state.settings.isMyTurn(pos.side)
            val lines = info?.lines.orEmpty().take(3).mapIndexed { i, l -> lineView(board, info!!, l, Lens.LineColors[i]) }
            val threat = state.threat?.let { t ->
                val nb = board.copy().also { it.makeNullMove() }
                val m = t.move
                if (m == Move.NONE || nb.pieceAt(Move.from(m)) == 0) null
                else LineView(t, m, San.of(nb, m), "threat", Lens.Red, Piece.type(nb.pieceAt(Move.from(m))) == Piece.KNIGHT)
            }
            val pv = lines.firstOrNull()?.line?.moves?.drop(1)?.take(3).orEmpty()
            val whiteCp = info?.whiteCp() ?: 0
            return OverlayModel(
                squares = pos.squares,
                sideToMove = pos.side,
                analysisMode = analysisMode,
                best = lines.firstOrNull(),
                alts = if (analysisMode) lines.drop(1).take(maxOf(0, state.settings.multiPv - 1)) else emptyList(),
                showBest = myTurn || analysisMode,
                lastMove = pos.lastMove,
                lastSan = pos.lastSan,
                checkSquare = if (pos.inCheck) pos.kingSquare else -1,
                threat = if (analysisMode && state.settings.showThreats) threat else null,
                hanging = if (analysisMode) pos.hanging else emptyList(),
                control = if (analysisMode && state.settings.showHeatmap) pos.control else null,
                pv = if (analysisMode && state.settings.showPv) pv else emptyList(),
                whiteWin = (Tactics.winPercent(whiteCp) / 100.0).toFloat(),
                evalText = info?.let { evalText(it, it.best) } ?: "",
                depth = info?.depth ?: 0,
                myTurn = myTurn,
            )
        }

        private fun lineView(board: Board, info: EngineInfo, l: EngineLine, color: Color): LineView {
            val m = l.move
            return LineView(l, m, San.of(board, m), evalText(info, l), color, Piece.type(board.pieceAt(Move.from(m))) == Piece.KNIGHT)
        }

        fun evalText(info: EngineInfo, line: EngineLine?): String {
            line ?: return ""
            val mate = info.whiteMate(line)
            if (mate != null) return if (mate > 0) "M$mate" else "-M${-mate}"
            val cp = info.whiteCp(line)
            return String.format(Locale.US, "%+.2f", cp / 100.0)
        }
    }
}
