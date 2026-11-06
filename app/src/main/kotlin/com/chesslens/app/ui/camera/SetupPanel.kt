package com.chesslens.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chesslens.app.game.SetupSource
import com.chesslens.app.game.SetupState
import com.chesslens.app.ui.board.PieceRenderer
import com.chesslens.app.ui.components.BevelButton
import com.chesslens.app.ui.components.BevelIconButton
import com.chesslens.app.ui.components.GlowButton
import com.chesslens.app.ui.components.Meter
import com.chesslens.app.ui.components.Segmented
import com.chesslens.app.ui.components.TabItem
import com.chesslens.app.ui.components.forgeWell
import com.chesslens.app.ui.theme.Lens
import com.chesslens.chess.Piece
import com.chesslens.chess.Square

/** Everything the set-up panel can ask the game for. */
data class SetupActions(
    val onSelect: (Int) -> Unit = {},
    val onPlace: (Int) -> Unit = {},
    val onSide: (Int) -> Unit = {},
    val onRescan: (anyAngle: Boolean) -> Unit = {},
    val onStandard: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onStart: () -> Unit = {},
)

/**
 * "New game": the camera reads the position, the user fixes what it couldn't see and says
 * whose move it is. Replaces the game panel while open so the camera keeps seeing the board.
 */
@Composable
fun SetupPanel(setup: SetupState, actions: SetupActions, landscape: Boolean, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New game", style = type.headlineSmall, color = Lens.Text)
                SetupStatus(setup)
            }
            BevelIconButton(Icons.Rounded.Close, "Cancel new game", onClick = actions.onCancel)
        }
        if (setup.scanning) {
            Meter(setup.progress)
            if (setup.canForce) {
                BevelButton(onClick = { actions.onRescan(true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DocumentScanner, null, tint = Lens.Amber, modifier = Modifier.size(18.dp))
                    Text("Scan from this angle anyway", style = type.labelMedium, color = Lens.Text)
                }
            }
        }
        if (landscape) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupBoard(setup, actions.onSelect, Modifier.fillMaxWidth())
                Editor(setup, actions)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupBoard(setup, actions.onSelect, Modifier.width(156.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Editor(setup, actions, compact = true)
                }
            }
        }
        GlowButton(
            "Start game",
            Icons.Rounded.PlayArrow,
            onClick = actions.onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = !setup.scanning && setup.problem == null,
        )
    }
}

@Composable
private fun SetupStatus(setup: SetupState) {
    val type = MaterialTheme.typography
    val unsure = setup.uncertain.size
    val (text, color) = when {
        setup.scanning && !setup.boardSeen -> "Point the camera at the whole board" to Lens.TextDim
        setup.scanning && setup.tooFlat -> "Hold the phone higher, looking down on the board" to Lens.Amber
        setup.scanning -> "Reading the board · hold still" to Lens.TextDim
        setup.problem != null -> setup.problem!! to Lens.Red
        setup.source == SetupSource.STANDARD -> "Starting position" to Lens.TextDim
        setup.source == SetupSource.SCAN_EXACT -> "Read ${setup.pieceCount} pieces · matches a known position" to Lens.Green
        unsure > 0 -> "Read ${setup.pieceCount} pieces · check the $unsure amber ${if (unsure == 1) "square" else "squares"}" to Lens.Amber
        else -> "Read ${setup.pieceCount} pieces" to Lens.Green
    }
    Text(text, style = type.bodyMedium, color = color, maxLines = 2)
}

/** Side to move, piece palette and the secondary actions. */
@Composable
private fun ColumnScope.Editor(setup: SetupState, actions: SetupActions, compact: Boolean = false) {
    Text("To move", style = MaterialTheme.typography.labelMedium, color = Lens.TextMuted)
    Segmented(
        listOf(TabItem("White"), TabItem("Black")),
        selected = setup.side,
        onSelect = actions.onSide,
        compact = true,
    )
    Palette(setup, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BevelButton(onClick = { actions.onRescan(false) }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
            Icon(Icons.Rounded.DocumentScanner, null, tint = Lens.Green, modifier = Modifier.size(18.dp))
            Text(if (compact) "Rescan" else "Scan again", style = MaterialTheme.typography.labelMedium, color = Lens.Text, maxLines = 1)
        }
        BevelButton(onClick = actions.onStandard, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
            Icon(Icons.Rounded.RestartAlt, null, tint = Lens.TextDim, modifier = Modifier.size(18.dp))
            Text(if (compact) "Initial" else "Initial position", style = MaterialTheme.typography.labelMedium, color = Lens.Text, maxLines = 1)
        }
    }
}

private val WHITE_PIECES = listOf(Piece.KING, Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT, Piece.PAWN).map { Piece.make(Piece.WHITE, it) }
private val BLACK_PIECES = listOf(Piece.KING, Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT, Piece.PAWN).map { Piece.make(Piece.BLACK, it) }

/** Pick a square on the board, then what stands there (✓ keeps the scanned piece). */
@Composable
private fun Palette(setup: SetupState, actions: SetupActions) {
    val enabled = setup.selected >= 0 && !setup.scanning
    val current = if (setup.selected >= 0) setup.squares[setup.selected] else -1
    Column(Modifier.fillMaxWidth().forgeWell(RoundedCornerShape(14.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in listOf(WHITE_PIECES, BLACK_PIECES)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (p in row) PaletteKey(Modifier.weight(1f), enabled, selected = p == current, description = "${if (Piece.color(p) == Piece.WHITE) "White" else "Black"} ${Piece.name(Piece.type(p))}", onClick = { actions.onPlace(p) }) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) { PieceRenderer.draw(this, p, Offset.Zero, size.width, if (enabled) 1f else 0.4f) }
                }
                if (row === WHITE_PIECES) {
                    PaletteKey(Modifier.weight(1f), enabled, selected = current == 0, description = "Empty square", onClick = { actions.onPlace(0) }) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = Color(0xFF3A2C1E).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
                    }
                } else {
                    val unsure = setup.selected in setup.uncertain
                    PaletteKey(Modifier.weight(1f), enabled && unsure, selected = false, description = "Scanned piece is right", onClick = { actions.onPlace(current) }) {
                        Icon(Icons.Rounded.Check, null, tint = if (enabled && unsure) Color(0xFF8A5A00) else Color(0xFF3A2C1E).copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteKey(modifier: Modifier, enabled: Boolean, selected: Boolean, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            // Light tiles so dark pieces read as clearly as light ones.
            .background(if (enabled) Lens.BoardLight else Lens.BoardLight.copy(alpha = 0.25f))
            .then(if (selected) Modifier.border(2.dp, Lens.Green, shape) else Modifier)
            .semantics { contentDescription = description }
            .pointerInput(enabled, onClick) { if (enabled) detectTapGestures { onClick() } }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Editable board: tap a square to select it; amber squares are the ones to check. */
@Composable
fun SetupBoard(setup: SetupState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Show the board as the camera sees it: Black's side nearest when the scan says so.
    val flipped = setup.orientation == 2
    Canvas(
        modifier
            .aspectRatio(1f)
            .forgeWell(RoundedCornerShape(14.dp))
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .semantics { contentDescription = "Set-up board" }
            .pointerInput(flipped, setup.scanning) {
                if (!setup.scanning) detectTapGestures { pos ->
                    val cell = size.width / 8f
                    val col = (pos.x / cell).toInt().coerceIn(0, 7)
                    val row = (pos.y / cell).toInt().coerceIn(0, 7)
                    onSelect(if (flipped) row * 8 + (7 - col) else (7 - row) * 8 + col)
                }
            },
    ) {
        val cell = size.width / 8f
        fun tl(sq: Int): Offset {
            val f = Square.file(sq)
            val r = Square.rank(sq)
            return if (flipped) Offset((7 - f) * cell, r * cell) else Offset(f * cell, (7 - r) * cell)
        }
        for (sq in 0 until 64) drawRect(if (Square.isLight(sq)) Lens.BoardLight else Lens.BoardDark, tl(sq), Size(cell, cell))
        // Nothing to show until the scan is in.
        if (!setup.scanning) for (sq in 0 until 64) {
            val p = setup.squares[sq]
            if (p != 0) PieceRenderer.draw(this, p, tl(sq), cell)
        }
        if (!setup.scanning) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(cell * 0.18f, cell * 0.1f))
            for (sq in setup.uncertain) {
                val o = tl(sq) + Offset(cell * 0.06f, cell * 0.06f)
                drawRect(Lens.Amber.copy(alpha = 0.18f), o, Size(cell * 0.88f, cell * 0.88f))
                drawRect(Lens.Amber, o, Size(cell * 0.88f, cell * 0.88f), style = Stroke(cell * 0.07f, pathEffect = dash))
            }
            if (setup.selected >= 0) {
                val o = tl(setup.selected)
                drawRect(Lens.Green, o + Offset(cell * 0.04f, cell * 0.04f), Size(cell * 0.92f, cell * 0.92f), style = Stroke(cell * 0.09f))
            }
        }
        if (setup.scanning) drawRect(Color.Black.copy(alpha = 0.25f))
    }
}
