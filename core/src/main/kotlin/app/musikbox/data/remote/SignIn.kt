package app.musikbox.data.remote

import app.musikbox.data.settings.Credentials
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Pings the server with token auth. If the user typed a bare host, tries HTTPS first and
 * falls back to plain HTTP (common for LAN installs).
 */
suspend fun SubsonicClient.signIn(rawServer: String, user: String, password: String): Result<Credentials> {
    val trimmed = rawServer.trim()
    val explicitScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    val candidates = buildList {
        add(SubsonicClient.normalizeServerUrl(trimmed))
        if (!explicitScheme) add(SubsonicClient.normalizeServerUrl("http://$trimmed"))
    }
    var last: Throwable? = null
    for (url in candidates) {
        val creds = runCatching { Credentials.create(url, user, password) }.getOrElse { return Result.failure(it) }
        try {
            ping(creds)
            return Result.success(creds)
        } catch (e: SubsonicException) {
            return Result.failure(e) // reached the server; auth or similar — don't retry over HTTP
        } catch (e: Exception) {
            last = e
        }
    }
    return Result.failure(last ?: IOException("Couldn't connect"))
}

fun Throwable.friendlySignInMessage(): String = when (this) {
    is SubsonicException -> when (code) {
        40, 41 -> "Wrong username or password."
        else -> message ?: "The server refused the request."
    }
    is SSLException -> "Secure connection failed. Check the address or certificate."
    is java.net.UnknownHostException -> "Can't find that server. Check the address."
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "The server isn't responding."
    is IllegalArgumentException -> "That server address doesn't look right."
    else -> message ?: "Couldn't connect."
}
