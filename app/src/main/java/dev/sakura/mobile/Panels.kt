package dev.sakura.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
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
        if (lighting == null || lighting.length() == 0) Text("No active RGB controller. Enable Dashboard lighting or start Sakura Lighting on your PC.")
        for ((id, label) in listOf("keyboard" to "Keyboard", "ram" to "RAM", "mouse" to "Mouse")) {
            val device = lighting?.optJSONObject(id)
            val initialColor = device?.optString("color") ?: "#f478d7"
            val initialBrightness = device?.optInt("brightness", 100)?.coerceIn(0, 100) ?: 100
            var color by remember(id, initialColor) { mutableStateOf(initialColor) }
            var brightness by remember(id, initialBrightness) { mutableFloatStateOf(initialBrightness.toFloat()) }
            val available = enabled && device != null
            OutlinedTextField(value = color, onValueChange = { if (it.length <= 7) color = it },
                label = { Text("$label colour") }, singleLine = true, enabled = available)
            var pickerOpen by remember(id) { mutableStateOf(false) }
            OutlinedButton(enabled = available, onClick = { pickerOpen = true }) {
                Text("Choose $label colour")
            }
            if (pickerOpen) ColourWheelDialog(label, color, { pickerOpen = false }) {
                color = it
                pickerOpen = false
            }
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

@Composable
private fun ColourWheelDialog(label: String, initial: String, dismiss: () -> Unit, apply: (String) -> Unit) {
    val hsv = remember(initial) {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(
                runCatching { android.graphics.Color.parseColor(initial) }.getOrDefault(0xfff478d7.toInt()), it)
        }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val selected = Color.hsv(hue, saturation, value)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("$label colour") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
                    fun pick(p: Offset) {
                        val dx = p.x - size.width / 2f
                        val dy = p.y - size.height / 2f
                        val radius = minOf(size.width, size.height) / 2f
                        hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
                        saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pick(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) { pick(change.position); change.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center), r)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), r)
                    drawCircle(Color.Black.copy(alpha = 1f - value), r)
                    val angle = Math.toRadians(hue.toDouble())
                    val marker = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * saturation)
                    drawCircle(Color.Black, 10.dp.toPx(), marker, style = Stroke(4.dp.toPx()))
                    drawCircle(Color.White, 10.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
                }
                Text("Colour intensity")
                Slider(value = value, onValueChange = { value = it })
                Surface(color = selected, modifier = Modifier.fillMaxWidth().height(32.dp), shape = MaterialTheme.shapes.small) {}
            }
        },
        confirmButton = { TextButton(onClick = {
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
            apply("#%06X".format(rgb and 0xffffff))
        }) { Text("Select colour") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}
