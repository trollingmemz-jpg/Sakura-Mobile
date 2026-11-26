package dev.sakura.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun SystemPanel(stats: JSONObject?) = Panel("System") {
    listOf("CPU" to "cpu", "Memory" to "memory", "GPU" to "gpu").forEach { (label, key) ->
        val value = stats?.optDouble(key)?.takeIf { it.isFinite() && it in 0.0..100.0 }
        Text(if (value == null) "$label · Unavailable" else "$label · ${value.roundToInt()}%")
        if (value != null) LinearProgressIndicator(
            progress = { value.toFloat() / 100 },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun MediaPanel(media: JSONObject?, enabled: Boolean, send: (String, JSONObject) -> Unit) =
    Panel("Media") {
        Text(media?.optString("title")?.ifBlank { null } ?: "No track")
        media?.optString("artist")?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Previous" to "previous", "Play / pause" to "play_pause", "Next" to "next")
                .forEach { (label, action) ->
                    OutlinedButton(enabled = enabled, contentPadding = PaddingValues(horizontal = 9.dp),
                        onClick = { send(action, JSONObject()) }) { Text(label) }
                }
        }
        val remoteVolume = media?.optInt("volume", -1)?.takeIf { it in 0..100 }
        var drag by remember { mutableStateOf<Float?>(null) }
        val volume = drag ?: (remoteVolume ?: 0).toFloat()
        Text(if (remoteVolume == null) "Volume unavailable" else "Volume · ${volume.roundToInt()}%")
        Slider(value = volume, valueRange = 0f..100f, enabled = enabled && remoteVolume != null,
            onValueChange = { drag = it }, onValueChangeFinished = {
                drag?.let { send("volume", JSONObject().put("percent", it.roundToInt())) }
                drag = null
            })
    }

@Composable
internal fun LightingPanel(lighting: JSONObject?, enabled: Boolean, send: (JSONObject) -> Unit) =
    Panel("Lighting") {
        if (lighting == null) Text("Lighting unavailable")
        for ((id, label) in listOf("keyboard" to "Keyboard", "ram" to "RAM", "mouse" to "Mouse")) {
            val device = lighting?.optJSONObject(id)
            val initialColor = device?.optString("color") ?: "#f478d7"
            val initialBrightness = device?.optInt("brightness", 100)?.coerceIn(0, 100) ?: 100
            var color by remember(id, initialColor) { mutableStateOf(initialColor) }
            var brightness by remember(id, initialBrightness) { mutableFloatStateOf(initialBrightness.toFloat()) }
            val available = enabled && device != null
            OutlinedTextField(value = color, onValueChange = { if (it.length <= 7) color = it },
                label = { Text("$label colour") }, singleLine = true, enabled = available)
            Text("$label brightness · ${brightness.roundToInt()}%")
            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0f..100f,
                enabled = available)
            OutlinedButton(enabled = available && color.matches(Regex("#[A-Fa-f0-9]{6}")),
                onClick = {
                    send(JSONObject().put("device", id).put("color", color)
                        .put("brightness", brightness.roundToInt()))
                }) { Text("Apply $label") }
        }
    }

@Composable
internal fun PowerPanel(online: Boolean, idle: Boolean, wake: () -> Unit, send: (String) -> Unit) =
    Panel("Power") {
        var confirmingSleep by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = online, onClick = { send("lock") }) { Text("Lock") }
            OutlinedButton(enabled = online, onClick = { confirmingSleep = true }) { Text("Sleep") }
            OutlinedButton(enabled = idle, onClick = wake) { Text("Wake") }
        }
        if (confirmingSleep) AlertDialog(
            onDismissRequest = { confirmingSleep = false },
            title = { Text("Put the PC to sleep?") },
            confirmButton = {
                TextButton(enabled = online, onClick = {
                    confirmingSleep = false
                    send("sleep")
                }) { Text("Sleep") }
            },
            dismissButton = { TextButton(onClick = { confirmingSleep = false }) { Text("Cancel") } },
        )
    }
