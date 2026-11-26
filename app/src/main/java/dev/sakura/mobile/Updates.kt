package dev.sakura.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class UpdateRelease(
    val versionCode: Long,
    val title: String,
    val notes: String,
    val assetId: Long,
    val size: Long,
    val digest: String,
) {
    companion object {
        private val apkName = Regex("sakura-mobile-([1-9][0-9]*)\\.apk")
        private val sha256 = Regex("sha256:([a-f0-9]{64})")
        const val MAX_APK_SIZE = 128L * 1024 * 1024

        fun parse(json: JSONObject, installed: Long): UpdateRelease? {
            val assets = json.getJSONArray("assets")
            val candidates = (0 until assets.length()).mapNotNull { index ->
                val asset = assets.getJSONObject(index)
                val code = apkName.matchEntire(asset.optString("name"))
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                code to asset
            }
            if (candidates.isEmpty()) return null
            check(candidates.size == 1) { "The release has multiple update APKs." }
            val (code, asset) = candidates.single()
            if (code <= installed) return null
            val digest = sha256.matchEntire(asset.optString("digest"))?.groupValues?.get(1)
                ?: error("GitHub did not supply an APK checksum.")
            val size = asset.getLong("size")
            val id = asset.getLong("id")
            check(size in 1..MAX_APK_SIZE && id > 0) { "The release APK metadata is invalid." }
            val tag = json.getString("tag_name")
            return UpdateRelease(code, json.optString("name").ifBlank { tag }, json.optString("body"),
                id, size, digest)
        }
    }
}

private const val PRIVATE_RELEASE_API = "https://api.github.com/repos/trollingmemz-jpg/Sakura-Mobile/releases"
private const val PUBLIC_RELEASE_API = "https://api.github.com/repos/trollingmemz-jpg/Sakura-Mobile-Releases/releases"
private fun releaseApi(token: String) = if (token.isBlank()) PUBLIC_RELEASE_API else PRIVATE_RELEASE_API
private const val APK_TYPE = "application/vnd.android.package-archive"

internal class UpdateClient(private val context: Context) {
    private val pm = context.packageManager
    private val installed by lazy { installedPackage() }
    val versionCode get() = installed.code()
    val versionName get() = installed.versionName.orEmpty()

    suspend fun check(token: String): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = connect(URL("${releaseApi(token)}/latest"), token, "application/vnd.github+json")
        try {
            when (request.responseCode) {
                200 -> {
                    val bytes = ByteArrayOutputStream()
                    request.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > 2 * 1024 * 1024) throw IOException("Release response is too large.")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    UpdateRelease.parse(JSONObject(bytes.toString("UTF-8")), versionCode)
                }
                401, 403, 404 -> error(if (token.isBlank()) "Public update channel is not available yet." else "GitHub could not open the latest release. Check the repository token.")
                else -> error("GitHub returned HTTP ${request.responseCode}.")
            }
        } finally {
            request.disconnect()
        }
    }

    suspend fun download(release: UpdateRelease, token: String, progress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates")
            val verified = File(directory, "verified")
            check(verified.isDirectory || verified.mkdirs()) { "Could not create the update folder." }
            val temporary = File.createTempFile("sakura-", ".apk", directory)
            try {
                val source = connect(URL("${releaseApi(token)}/assets/${release.assetId}"), token, "application/octet-stream")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                try {
                    val redirected = if (source.responseCode in listOf(301, 302, 303, 307, 308)) {
                        val url = URL(source.getHeaderField("Location") ?: error("Missing APK download URL."))
                        check(url.protocol == "https" && url.userInfo == null &&
                            (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) {
                            "GitHub returned an unexpected download address."
                        }
                        connect(url, "", "application/octet-stream")
                    } else source
                    try {
                        check(redirected.responseCode == 200) { "APK download returned HTTP ${redirected.responseCode}." }
                        redirected.inputStream.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    check(received <= release.size) { "The APK is larger than expected." }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    progress(received)
                                }
                            }
                        }
                    } finally {
                        if (redirected !== source) redirected.disconnect()
                    }
                } finally {
                    source.disconnect()
                }
                check(received == release.size && digest.digest().hex() == release.digest) {
                    "The APK checksum did not match. Download it again."
                }
                verifyArchive(temporary, release.versionCode)
                val apk = File(verified, "sakura-mobile-${release.versionCode}.apk")
                check(temporary.renameTo(apk)) { "Could not store the verified APK." }
                apk
            } finally {
                temporary.delete()
            }
        }

    @Suppress("DEPRECATION")
    internal fun verifyArchive(apk: File, expected: Long) {
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            ?: error("Android could not read the downloaded APK.")
        check(archive.packageName == context.packageName && archive.code() == expected && expected > versionCode) {
            "The APK package or version does not match the update."
        }
        val current = installed.certificates()
        check(current.isNotEmpty() && current == archive.certificates()) {
            "The APK signing certificate does not match the installed app."
        }
    }

    fun installer(apk: File): Intent {
        if (!pm.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun connect(url: URL, token: String, accept: String) =
        (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (url.host == "api.github.com") setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    @Suppress("DEPRECATION")
    private fun installedPackage() = pm.getPackageInfo(context.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.certificates(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
