package app.musikbox.sync

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Devices of one account share a sync key derived from the Navidrome password at sign-in
 * (PBKDF2, 120k rounds) — the password itself is never stored. Every sync request is signed
 * with HMAC-SHA256 over method, path, timestamp and body hash, and rejected if stale.
 */
object SyncCrypto {
    const val MAX_SKEW_MS = 2 * 60_000L

    fun deriveKey(username: String, password: String): ByteArray {
        val salt = "musikbox-sync-v1|${username.trim().lowercase()}".toByteArray()
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun fingerprint(key: ByteArray): String =
        sha256("musikbox-fp|".toByteArray() + key).toHex().take(16)

    fun sign(key: ByteArray, method: String, path: String, timeMs: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val msg = "$method\n$path\n$timeMs\n${sha256(body).toHex()}"
        return mac.doFinal(msg.toByteArray()).toHex()
    }

    fun verify(key: ByteArray, method: String, path: String, timeMs: Long, body: ByteArray, signature: String, now: Long = System.currentTimeMillis()): Boolean {
        if (kotlin.math.abs(now - timeMs) > MAX_SKEW_MS) return false
        val expected = sign(key, method, path, timeMs, body)
        return MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
