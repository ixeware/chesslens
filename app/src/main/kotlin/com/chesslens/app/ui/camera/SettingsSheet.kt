package com.chesslens.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.Settings
import com.chesslens.app.ui.theme.Lens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lens.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Section("I'm playing")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in PlayAs.entries) {
                    FilterChip(
                        selected = settings.playAs == p,
                        onClick = { onChange { it.copy(playAs = p) } },
                        label = { Text(p.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lens.Neon, selectedLabelColor = Lens.PillText),
                    )
                }
            }
            Text("Hints are shown on your turn; the opponent's move is highlighted in yellow.", color = Lens.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Section("Engine")
            LabeledSlider("Think time", "${settings.thinkTimeMs / 1000.0}s", settings.thinkTimeMs.toFloat(), 500f..10000f) { v ->
                onChange { it.copy(thinkTimeMs = (v / 250).toInt() * 250) }
            }
            LabeledSlider("Lines (MultiPV)", "${settings.multiPv}", settings.multiPv.toFloat(), 1f..3f, steps = 1) { v ->
                onChange { it.copy(multiPv = v.toInt().coerceIn(1, 3)) }
            }
            Toggle("Lichess cloud analysis", "Deeper evaluations for known positions (needs internet)", settings.cloudEval) { b -> onChange { it.copy(cloudEval = b) } }
            Spacer(Modifier.height(8.dp))
            Section("Analysis overlay")
            Toggle("Threat arrow", "What the opponent threatens if you pass", settings.showThreats) { b -> onChange { it.copy(showThreats = b) } }
            Toggle("Principal variation", "Numbered follow-up moves on the board", settings.showPv) { b -> onChange { it.copy(showPv = b) } }
            Toggle("Control heat-map", "Squares controlled by White (green) / Black (red)", settings.showHeatmap) { b -> onChange { it.copy(showHeatmap = b) } }
            Spacer(Modifier.height(8.dp))
            Section("Camera")
            Toggle("Auto board orientation", "Detect which side White sits on from the start position", settings.autoOrientation) { b -> onChange { it.copy(autoOrientation = b) } }
            Toggle("Voice hints", "Speak the best move", settings.voice) { b -> onChange { it.copy(voice = b) } }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Lens.Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = Lens.TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Lens.Neon, checkedThumbColor = Lens.PillText),
        )
    }
}

@Composable
private fun LabeledSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Lens.Neon, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = current.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(thumbColor = Lens.Neon, activeTrackColor = Lens.Neon),
    )
}

@Composable
fun FenDialog(onDismiss: () -> Unit, onLoad: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set position") },
        text = {
            Column {
                Text("Paste a FEN to continue from a custom position.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, placeholder = { Text("rnbqkbnr/pppppppp/8/8/…") })
            }
        },
        confirmButton = { TextButton(onClick = { onLoad(text.trim()) }) { Text("Load") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Lens.Surface,
    )
}
