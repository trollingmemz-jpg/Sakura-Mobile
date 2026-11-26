package dev.sakura.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal class PcClient(private val pc: Connection) {
    private val pinnedCertificate = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are unsupported")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Missing PC certificate")
            leaf.checkValidity()
            val hash = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            if (hash != pc.fingerprint) throw CertificateException("PC certificate changed")
        }
    }
    private val tls = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(pinnedCertificate), null)
    }

    suspend fun status() = request("status")

    suspend fun command(action: String, values: JSONObject = JSONObject()) {
        request("command", values.put("action", action))
    }

    private suspend fun request(path: String, body: JSONObject? = null) = withContext(Dispatchers.IO) {
        val url = URI("https", null, pc.host, pc.port, "/v1/$path", null, null).toURL()
        val http = (url.openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            // The certificate pin identifies the PC even when DHCP changes its address.
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            instanceFollowRedirects = false
            connectTimeout = 3000
            readTimeout = 3000
            setRequestProperty("Authorization", "Bearer ${pc.token}")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) http.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            val status = http.responseCode
            check(status in 200..299) { "PC returned HTTP $status" }
            if (status == 204) JSONObject() else http.inputStream.use {
                val bytes = readResponse(it)
                JSONObject(bytes.decodeToString())
            }
        } finally {
            http.disconnect()
        }
    }

    private fun readResponse(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            check(output.size() + count <= 65536) { "PC response is too large" }
            output.write(buffer, 0, count)
        }
    }

    suspend fun wake() = withContext(Dispatchers.IO) {
        val address = pc.mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val payload = ByteArray(6) { 0xff.toByte() } + ByteArray(96) { address[it % 6] }
        DatagramSocket().use {
            it.broadcast = true
            it.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 9))
        }
    }
}
