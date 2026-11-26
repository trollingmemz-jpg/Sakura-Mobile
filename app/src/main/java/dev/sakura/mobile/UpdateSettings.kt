package dev.sakura.mobile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@Composable
internal fun UpdateMenu(context: Context) {
    val client = remember(context) { UpdateClient(context) }
    val storage = remember(context) { EncryptedPreference(context, "sakura_updates", "github_token") }
    var token by remember { mutableStateOf(runCatching { storage.load() }.getOrNull().orEmpty()) }
    var draft by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Long?>(null) }
    var release by remember { mutableStateOf<UpdateRelease?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var notice by remember {
        mutableStateOf(if (token.isBlank()) "Add a GitHub read token to receive updates." else "")
    }
    val scope = rememberCoroutineScope()

    suspend fun check(showAvailable: Boolean) {
        if (busy || token.isBlank()) return
        busy = true
        notice = "Checking GitHub…"
        try {
            val result = client.check(token)
            if (release != result) apk = null
            release = result
            notice = if (result == null) "Sakura Mobile is up to date." else "Update available"
            if (result != null && showAvailable) open = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            notice = "GitHub is unreachable. You can still use Sakura on your local network."
        } catch (e: Exception) {
            notice = e.message ?: "Could not check for updates."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { check(showAvailable = true) }

    OutlinedButton(onClick = { open = true }) { Text("Settings") }

    if (open) AlertDialog(
        onDismissRequest = { open = false; draft = "" },
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = { open = false; draft = "" }) { Text("Close") } },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("App updates", style = MaterialTheme.typography.titleMedium)
                Text("Installed version ${client.versionName}")
                Text(notice)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 512) draft = it },
                    label = { Text(if (token.isEmpty()) "GitHub read token" else "Replace GitHub read token") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (token.isBlank()) Text("Use a token with Contents read access to the private Sakura-Mobile repository.")
                if (draft.isNotBlank()) TextButton(enabled = !busy, onClick = {
                    val value = draft.trim()
                    if (!value.matches(Regex("[A-Za-z0-9_]{20,512}"))) {
                        notice = "Enter a valid GitHub token."
                    } else if (runCatching { storage.save(value) }.isSuccess) {
                        token = value
                        draft = ""
                        scope.launch { check(showAvailable = false) }
                    } else {
                        notice = "Could not save the GitHub token."
                    }
                }) { Text("Save token") }
                if (token.isNotBlank()) TextButton(enabled = !busy, onClick = {
                    if (runCatching { storage.clear() }.isSuccess) {
                        token = ""
                        draft = ""
                        release = null
                        apk = null
                        notice = "GitHub token removed."
                    } else {
                        notice = "Could not remove the GitHub token."
                    }
                }) { Text("Remove token") }
                OutlinedButton(enabled = !busy && token.isNotEmpty(), onClick = {
                    scope.launch { check(showAvailable = false) }
                }) { Text("Check for Updates") }

                val available = release
                if (available != null) {
                    HorizontalDivider()
                    Text(available.title, style = MaterialTheme.typography.titleMedium)
                    if (available.notes.isNotBlank()) Text(available.notes)
                    val received = progress
                    if (received != null) {
                        val fraction = (received.toDouble() / available.size).toFloat()
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction * 100).toInt()}% downloaded")
                    }
                    val downloaded = apk?.takeIf { it.isFile }
                    if (downloaded != null) Button(enabled = !busy, onClick = {
                        val allowed = context.packageManager.canRequestPackageInstalls()
                        runCatching { context.startActivity(client.installer(downloaded)) }
                            .onSuccess {
                                if (!allowed) notice = "Allow installs from Sakura Mobile, then return and tap Install update."
                            }
                            .onFailure { notice = "Could not open the Android installer." }
                    }) { Text("Install update") }
                    else Button(enabled = !busy && token.isNotEmpty(), onClick = {
                        busy = true
                        progress = 0
                        notice = "Downloading APK…"
                        scope.launch {
                            try {
                                apk = client.download(available, token) { progress = it }
                                notice = "APK verified. Ready to install."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: IOException) {
                                notice = "The APK download failed. Check your connection and try again."
                            } catch (e: Exception) {
                                notice = e.message ?: "The update could not be verified."
                            } finally {
                                busy = false
                                progress = null
                            }
                        }
                    }) { Text("Download APK") }
                }
            }
        },
    )
}
