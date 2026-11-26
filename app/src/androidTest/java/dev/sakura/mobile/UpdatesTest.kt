package dev.sakura.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdatesTest {
    private fun release() = JSONObject()
        .put("tag_name", "v0.10.0")
        .put("name", "New Sakura")
        .put("body", "Release notes")
        .put("assets", JSONArray().put(asset()))

    private fun asset() = JSONObject()
        .put("name", "sakura-mobile-10.apk")
        .put("id", 45)
        .put("size", 4096)
        .put("digest", "sha256:" + "a".repeat(64))

    @Test
    fun comparesVersionCodesAndReadsReleaseMetadata() {
        val result = checkNotNull(UpdateRelease.parse(release(), 9))
        assertEquals(10L, result.versionCode)
        assertEquals(45L, result.assetId)
        assertEquals(4096L, result.size)
        assertEquals("Release notes", result.notes)
        assertNull(UpdateRelease.parse(release(), 10))
        assertNull(UpdateRelease.parse(release(), 11))
    }

    @Test
    fun rejectsUnverifiableOrAmbiguousAssets() {
        val missingDigest = release().put("assets", JSONArray().put(asset().apply { remove("digest") }))
        assertTrue(runCatching { UpdateRelease.parse(missingDigest, 1) }.isFailure)
        val invalidSize = release().put("assets", JSONArray().put(asset().put("size", 0)))
        assertTrue(runCatching { UpdateRelease.parse(invalidSize, 1) }.isFailure)
        val ambiguous = release().put("assets", JSONArray().put(asset()).put(asset()))
        assertTrue(runCatching { UpdateRelease.parse(ambiguous, 1) }.isFailure)
    }

    @Test
    fun rejectsReinstallAndCorruptArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = UpdateClient(context)
        val installed = File(context.applicationInfo.sourceDir)
        assertTrue(runCatching { client.verifyArchive(installed, client.versionCode) }.isFailure)
        val corrupt = File.createTempFile("corrupt-update", ".apk", context.cacheDir)
        try {
            corrupt.writeText("not an APK")
            assertTrue(runCatching { client.verifyArchive(corrupt, client.versionCode + 1) }.isFailure)
        } finally {
            corrupt.delete()
        }
    }
}
