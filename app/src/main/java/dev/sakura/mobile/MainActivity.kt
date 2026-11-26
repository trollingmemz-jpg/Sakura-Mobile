package dev.sakura.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xfff478d7),
                onPrimary = Color(0xff32132e),
                background = Color(0xff100b20),
                surface = Color(0xff201329),
                surfaceVariant = Color(0xff39253e),
                onBackground = Color(0xfff5d9ef),
                onSurface = Color(0xfff5d9ef),
            )) {
                SakuraApp()
            }
        }
    }
}

@Composable
private fun SakuraApp() {
    val activityContext = LocalContext.current
    val context = activityContext.applicationContext
    val store = remember { PairingStore(context) }
    val saved = remember { runCatching { store.load() } }
    var paired by remember { mutableStateOf(saved.getOrNull()) }
    var address by remember(paired) { mutableStateOf(paired) }
    val client = remember(address) { address?.let(::PcClient) }
    var status by remember(client) { mutableStateOf<JSONObject?>(null) }
    var notice by remember { mutableStateOf(if (saved.isFailure) "Scan the PC pairing QR again." else "") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val candidate = Connection.parse(raw)
                PcClient(candidate).status()
                store.save(candidate)
                paired = candidate
                notice = "PC paired."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = "Pairing failed. Check the QR code and whether the PC is reachable."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(paired, lifecycle) {
        val pc = paired ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            discoverPc(context, pc.fingerprint).catch { }.collect { (host, port) ->
                val discovered = runCatching { pc.copy(host = host, port = port) }.getOrNull()
                    ?: return@collect
                try {
                    PcClient(discovered).status()
                    address = discovered
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only adopt an address after authenticating it.
                }
            }
        }
    }
    LaunchedEffect(client, lifecycle) {
        val active = client ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                status = try {
                    active.status()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                delay(2000)
            }
        }
    }

    fun perform(block: suspend PcClient.() -> Unit, success: String = "") {
        val active = client ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                active.block()
                notice = success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = "The PC could not complete the request."
            } finally {
                busy = false
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sakura Mobile", style = MaterialTheme.typography.headlineMedium)
                UpdateMenu(activityContext)
            }
            Text(when {
                paired == null -> "Scan the pairing QR displayed on your PC."
                status == null -> "PC offline or connecting"
                else -> status?.optString("name", "PC connected").orEmpty()
            })
            if (notice.isNotBlank()) Text(notice)
            if (paired == null) {
                Button(enabled = !busy, onClick = {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the Sakura PC pairing QR").setBeepEnabled(false))
                }) { Text("Scan pairing QR") }
            } else {
                val online = status != null && !busy
                SystemPanel(status?.optJSONObject("stats"))
                MediaPanel(status?.optJSONObject("media"), online) { action, fields ->
                    perform({ command(action, fields) })
                }
                LightingPanel(status?.optJSONObject("lighting"), online) { values ->
                    perform({ command("lighting", values) })
                }
                PowerPanel(online, !busy, {
                    perform({ wake() }, "Wake packet sent.")
                }, { action ->
                    perform({ command(action) })
                })
                TextButton(enabled = !busy, onClick = {
                    runCatching { store.clear() }.onSuccess {
                        paired = null
                        notice = ""
                    }.onFailure { notice = "Could not remove pairing." }
                }) { Text("Forget PC") }
            }
        }
    }
}
