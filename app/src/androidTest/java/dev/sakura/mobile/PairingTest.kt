package dev.sakura.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@RunWith(AndroidJUnit4::class)
class PairingTest {
    private val pairing = Connection("127.0.0.1", 8443, "a".repeat(64),
        "test_token_".repeat(4), "12:34:56:78:9a:bc")

    @Test
    fun validatesQrAndRoundTripsAndroidKeystore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "sakura_pairing_test"
        val store = PairingStore(context, name)
        try {
            assertEquals(pairing, Connection.parse(pairing.json().toString()))
            assertTrue(runCatching { Connection.parse(pairing.json().put("version", 2).toString()) }.isFailure)
            assertTrue(runCatching { Connection.parse(pairing.json().put("token", "short").toString()) }.isFailure)
            store.save(pairing)
            assertEquals(pairing, PairingStore(context, name).load())
            val stored = context.getSharedPreferences(name, 0).getString("connection", "")
            assertFalse(stored.orEmpty().contains(pairing.token))
        } finally {
            store.clear()
        }
        assertNull(PairingStore(context, name).load())
    }

    @Test
    fun sendsAuthenticatedStatusAndCommandAndRejectsChangedCertificate() = runBlocking {
        val identity = HeldCertificate.Builder().commonName("Sakura test PC").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(identity).build()
        val pin = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val pc = pairing.copy(port = server.port, fingerprint = pin)
            val client = PcClient(pc)
            server.enqueue(MockResponse().setBody("""{"stats":{"cpu":21}}"""))
            assertEquals(21, client.status().getJSONObject("stats").getInt("cpu"))
            val status = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/status", status.path)
            assertEquals("Bearer ${pc.token}", status.getHeader("Authorization"))

            server.enqueue(MockResponse().setResponseCode(204))
            client.command("volume", JSONObject().put("percent", 42))
            val command = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/command", command.path)
            assertEquals("POST", command.method)
            val fields = JSONObject(command.body.readUtf8())
            assertEquals("volume", fields.getString("action"))
            assertEquals(42, fields.getInt("percent"))

            val rejected = runCatching { PcClient(pc.copy(fingerprint = "b".repeat(64))).status() }
            assertTrue(rejected.exceptionOrNull() is SSLException)
            assertEquals(2, server.requestCount)
        }
    }
}
