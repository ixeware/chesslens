package com.chesslens.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.chesslens.app.game.PlayAs
import com.chesslens.app.game.Settings
import com.chesslens.app.ui.components.IconTile
import com.chesslens.app.ui.components.Segmented
import com.chesslens.app.ui.components.TabItem
import com.chesslens.app.ui.components.forgeSlab
import com.chesslens.app.ui.theme.Lens
import java.util.Locale

/** Tile colours (light, deep) for settings rows. Category colour, not decoration. */
private object Tiles {
    val green = Color(0xFF2EE88A) to Color(0xFF004D25)
    val blue = Color(0xFF3D8BFF) to Color(0xFF002B7A)
    val magenta = Color(0xFFE040FB) to Color(0xFF5A0F6E)
    val amber = Color(0xFFFFB020) to Color(0xFF6B3A00)
    val cyan = Color(0xFF22D3EE) to Color(0xFF064E5C)
    val red = Color(0xFFFF5A5F) to Color(0xFF6E0F14)
    val violet = Color(0xFF9B7BFF) to Color(0xFF2E1A7A)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lens.Ground) {
        SettingsContent(settings, onChange, Modifier.navigationBarsPadding())
    }
}

/** Settings rows (also rendered standalone in screenshots). */
@Composable
fun SettingsContent(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, modifier: Modifier = Modifier) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Lens.Text, modifier = Modifier.padding(horizontal = 4.dp))
            Group("Game") {
                SettingRow(Icons.Rounded.Person, Tiles.green, "I'm playing", "Hints show on your turn; the opponent's move turns yellow.")
                Segmented(
                    PlayAs.entries.map { TabItem(it.label) },
                    settings.playAs.ordinal,
                    { i -> onChange { it.copy(playAs = PlayAs.entries[i]) } },
                    Modifier.padding(start = 54.dp),
                    compact = true,
                )
                SettingRow(Icons.Rounded.Visibility, Tiles.blue, "What the camera sees", "A real board, or a board on a screen or printout.")
                SurfaceSwitch(settings.surface, { s -> onChange { it.copy(surface = s) } }, Modifier.padding(start = 54.dp))
            }
            Group("Engine") {
                SettingRow(Icons.Rounded.Timer, Tiles.amber, "Think time", null, trailing = String.format(Locale.US, "%.1fs", settings.thinkTimeMs / 1000.0))
                Slider(
                    value = settings.thinkTimeMs.toFloat().coerceIn(500f, 10000f),
                    onValueChange = { v -> onChange { it.copy(thinkTimeMs = (v / 250).toInt() * 250) } },
                    valueRange = 500f..10000f,
                    colors = SliderDefaults.colors(thumbColor = Lens.Green, activeTrackColor = Lens.Green, inactiveTrackColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.padding(start = 54.dp),
                )
                SettingRow(Icons.AutoMirrored.Rounded.CallSplit, Tiles.violet, "Engine lines", "Arrows for the best alternatives in Analysis.", trailing = "${settings.multiPv}")
                Slider(
                    value = settings.multiPv.toFloat(),
                    onValueChange = { v -> onChange { it.copy(multiPv = v.toInt().coerceIn(1, 3)) } },
                    valueRange = 1f..3f,
                    steps = 1,
                    colors = SliderDefaults.colors(thumbColor = Lens.Green, activeTrackColor = Lens.Green, inactiveTrackColor = Color(0xFF2A2A2A)),
                    modifier = Modifier.padding(start = 54.dp),
                )
                Toggle(Icons.Rounded.Cloud, Tiles.cyan, "Lichess cloud analysis", "Deeper evaluations for known positions. Needs internet.", settings.cloudEval) { b -> onChange { it.copy(cloudEval = b) } }
            }
            Group("Analysis overlay") {
                Toggle(Icons.Rounded.Warning, Tiles.red, "Threat arrow", "What the opponent threatens if you pass.", settings.showThreats) { b -> onChange { it.copy(showThreats = b) } }
                Toggle(Icons.Rounded.Timeline, Tiles.green, "Main line", "Numbered follow-up moves on the board.", settings.showPv) { b -> onChange { it.copy(showPv = b) } }
                Toggle(Icons.Rounded.GridOn, Tiles.amber, "Control heat map", "Squares held by White (green) and Black (red).", settings.showHeatmap) { b -> onChange { it.copy(showHeatmap = b) } }
            }
            Group("Camera and voice") {
                Toggle(Icons.Rounded.ScreenRotation, Tiles.blue, "Find White automatically", "Reads the start position to see which side White is on.", settings.autoOrientation) { b -> onChange { it.copy(autoOrientation = b) } }
                Toggle(Icons.Rounded.RecordVoiceOver, Tiles.magenta, "Speak the best move", null, settings.voice) { b -> onChange { it.copy(voice = b) } }
            }
            Spacer(Modifier.height(12.dp))
        }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().forgeSlab(RoundedCornerShape(24.dp), elevation = 6.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Lens.Text)
        content()
    }
}

@Composable
private fun SettingRow(icon: ImageVector, tile: Pair<Color, Color>, title: String, subtitle: String?, trailing: String? = null, control: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon, tile.first, tile.second)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Lens.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lens.TextMuted)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleLarge, color = Lens.Green)
        control?.invoke()
    }
}

@Composable
private fun Toggle(icon: ImageVector, tile: Pair<Color, Color>, title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, tile, title, subtitle) {
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Lens.Green,
                checkedThumbColor = Lens.GreenInk,
                uncheckedTrackColor = Color(0xFF1C1C1C),
                uncheckedBorderColor = Lens.ControlBorder,
                uncheckedThumbColor = Lens.TextMuted,
            ),
        )
    }
}

@Composable
fun FenDialog(onDismiss: () -> Unit, onLoad: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start from a position", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text("Paste a FEN. Then set the pieces on the board the same way and keep playing.", style = MaterialTheme.typography.bodyMedium, color = Lens.TextDim)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("rnbqkbnr/pppppppp/8/8/…", color = Lens.TextFaint) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lens.Green, cursorColor = Lens.Green, unfocusedBorderColor = Lens.ControlBorder),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onLoad(text.trim()) }, enabled = text.isNotBlank()) { Text("Load", color = Lens.Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Lens.TextDim) } },
        containerColor = Lens.SlabLit,
    )
}
