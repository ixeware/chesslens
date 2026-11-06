package com.chesslens.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.chesslens.app.ui.ar.ArProjector
import com.chesslens.app.ui.components.GlassChip
import com.chesslens.app.ui.theme.Lens

/** Drag the four corners onto the playing area (inner 8x8 grid) to lock the board manually. */
@Composable
fun CornerEditor(initial: List<Offset>?, onDone: (List<Offset>) -> Unit, onCancel: () -> Unit) {
    var active by remember { mutableIntStateOf(-1) }
    val touch = with(LocalDensity.current) { 48.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val corners = remember {
            mutableStateListOf<Offset>().apply {
                addAll(
                    initial ?: run {
                        val s = minOf(w, h) * 0.6f
                        listOf(
                            Offset(w / 2 - s / 2, h / 2 - s / 2), Offset(w / 2 + s / 2, h / 2 - s / 2),
                            Offset(w / 2 + s / 2, h / 2 + s / 2), Offset(w / 2 - s / 2, h / 2 + s / 2),
                        )
                    },
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            if (corners.isEmpty()) return@detectDragGestures
                            val i = corners.indices.minBy { (corners[it] - pos).getDistance() }
                            active = if ((corners[i] - pos).getDistance() < touch * 1.5f) i else -1
                        },
                        onDragEnd = { active = -1 },
                        onDrag = { change, delta ->
                            if (active >= 0) {
                                change.consume()
                                corners[active] = corners[active] + delta
                            }
                        },
                    )
                },
        ) {
            drawRect(Color.Black.copy(alpha = 0.25f))
            val pj = ArProjector(corners.toList(), 0)
            for (k in 0..8) {
                drawLine(Lens.Neon.copy(alpha = 0.55f), pj.board(k.toFloat(), 0f), pj.board(k.toFloat(), 8f), strokeWidth = 1.5f)
                drawLine(Lens.Neon.copy(alpha = 0.55f), pj.board(0f, k.toFloat()), pj.board(8f, k.toFloat()), strokeWidth = 1.5f)
            }
            val outline = Path().apply {
                corners.forEachIndexed { i, c -> if (i == 0) moveTo(c.x, c.y) else lineTo(c.x, c.y) }
                close()
            }
            drawPath(outline, Lens.Neon, style = Stroke(3.dp.toPx()))
            corners.forEachIndexed { i, c ->
                drawCircle(Lens.Neon.copy(alpha = 0.25f), touch / 2, c)
                drawCircle(if (i == active) Color.White else Lens.Neon, 9.dp.toPx(), c)
                drawCircle(Color.Black.copy(alpha = 0.6f), 3.dp.toPx(), c)
            }
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlassChip { Text("Drag the corners onto the 8×8 playing area", color = Color.White) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            Button(
                onClick = { onDone(corners.toList()) },
                colors = ButtonDefaults.buttonColors(containerColor = Lens.Neon, contentColor = Lens.PillText),
            ) { Text("Lock board") }
        }
    }
}
