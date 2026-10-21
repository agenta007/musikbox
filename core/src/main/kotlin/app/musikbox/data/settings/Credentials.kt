package app.musikbox.data.settings

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic token auth. Only the salted MD5 token is persisted — never the password.
 * The salt is fixed per login so that stream and cover-art URLs stay stable, which keeps
 * Coil's image cache and offline artwork working across launches.
 */
data class Credentials(
    val serverUrl: String,
    val username: String,
    val salt: String,
    val token: String,
) {
    companion object {
        fun create(serverUrl: String, username: String, password: String): Credentials {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val salt = bytes.joinToString("") { "%02x".format(it) }
            return Credentials(serverUrl, username, salt, md5(password + salt))
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
