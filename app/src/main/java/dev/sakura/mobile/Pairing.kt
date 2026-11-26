package dev.sakura.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class Connection(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val mac: String,
) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/?#@%[]" })
        require(port in 1..65535)
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        require(mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
    }

    fun json() = JSONObject().put("version", 1).put("host", host).put("port", port)
        .put("fingerprint", fingerprint).put("token", token).put("mac", mac)

    companion object {
        fun parse(raw: String): Connection {
            require(raw.length <= 2048)
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            return Connection(json.getString("host"), json.getInt("port"),
                json.getString("fingerprint"), json.getString("token"), json.getString("mac"))
        }
    }
}

internal class PairingStore(context: Context, name: String = "sakura_pairing") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "${name}_v1"

    private fun key(): SecretKey = (keys.getKey(alias, null) as? SecretKey)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }

    fun load(): Connection? {
        val blob = prefs.getString("connection", null) ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return Connection.parse(cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString())
    }

    fun save(connection: Connection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(connection.json().toString().encodeToByteArray())
        check(prefs.edit().putString("connection", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
    }

    fun clear() {
        check(prefs.edit().remove("connection").commit())
    }
}
