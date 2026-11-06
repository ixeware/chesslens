package com.chesslens.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesslens.app.game.OverlayMode
import com.chesslens.app.ui.ar.ArOverlay
import com.chesslens.app.ui.ar.OverlayFx
import com.chesslens.app.ui.camera.DemoBackdrop
import com.chesslens.app.ui.camera.demoQuad
import com.chesslens.app.ui.components.AppMark
import com.chesslens.app.ui.components.GlassChip
import com.chesslens.app.ui.components.StatusDot
import com.chesslens.app.ui.theme.Lens
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onStartCamera: () -> Unit, onAnalysis: () -> Unit, animate: Boolean = true) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Lens.Background)
            .background(Brush.radialGradient(listOf(Lens.Neon.copy(alpha = 0.12f), Color.Transparent), radius = 900f)),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hero(Modifier.weight(1.2f).fillMaxHeight(), animate)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Intro(onStartCamera, onAnalysis) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Hero(Modifier.fillMaxWidth().height(300.dp), animate)
                Spacer(Modifier.height(24.dp))
                Intro(onStartCamera, onAnalysis)
            }
        }
    }
}

@Composable
private fun Hero(modifier: Modifier, animate: Boolean) {
    val heroState = remember { DemoData.heroState(OverlayMode.HINT) }
    var fx by remember { mutableStateOf(OverlayFx(lockNanos = if (animate) System.nanoTime() else 0L)) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(7000)
            fx = OverlayFx(lockNanos = System.nanoTime())
        }
    }
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, Lens.GlassBorder, RoundedCornerShape(28.dp)),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val quad = remember(w, h) { demoQuad(w, h) }
        DemoBackdrop(heroState.position.squares, orientation = 2)
        ArOverlay(DemoData.vision(quad, 2), heroState, fx, Modifier.fillMaxSize())
        GlassChip(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Text("Tracking", color = Color.White, fontSize = 12.sp)
            StatusDot(Lens.Neon, pulse = false)
        }
    }
}

@Composable
private fun Intro(onStartCamera: () -> Unit, onAnalysis: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(64.dp)
        Spacer(Modifier.height(14.dp))
        Text("ChessLens", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Text(
            buildAnnotatedString {
                append("The best move, ")
                withStyle(SpanStyle(color = Lens.Neon, fontWeight = FontWeight.SemiBold)) { append("right on the board.") }
            },
            color = Lens.TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feature("Camera")
            Feature("Augmented Reality")
            Feature("Analysis")
        }
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onStartCamera,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Lens.Neon, contentColor = Lens.PillText),
        ) { Text("Start camera", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAnalysis, modifier = Modifier.fillMaxWidth().height(50.dp), shape = CircleShape) {
            Text("Analysis board", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("On-device engine · Lichess cloud eval · Voice hints", color = Lens.TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun Feature(label: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Lens.GlassBorder, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
