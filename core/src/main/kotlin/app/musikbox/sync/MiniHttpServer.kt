package app.musikbox.sync

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

data class HttpRequest(
    val method: String,
    val path: String,
    /** Lower-cased header names. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteHost: String,
)

data class HttpResponse(val code: Int, val body: String = "", val contentType: String = "application/json")

/**
 * Minimal HTTP/1.1 server (one request per connection, Content-Length bodies only).
 * Enough for the small JSON sync API, and it runs unchanged on Android, which has no
 * com.sun.net.httpserver.
 */
class MiniHttpServer(private val port: Int, private val handler: (HttpRequest) -> HttpResponse, private val fallbacks: Int = 0) {
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mb-sync-http").apply { isDaemon = true } }

    val isRunning get() = socket?.isClosed == false

    /** Binds [port], or the next free one within [fallbacks]; returns the port actually bound. */
    fun start(): Result<Int> = runCatching {
        var last: Exception? = null
        for (p in port..port + fallbacks) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
            } catch (e: Exception) {
                runCatching { s.close() }
                last = e
                continue
            }
            socket = s
            Thread({ acceptLoop(s) }, "mb-sync-accept").apply { isDaemon = true }.start()
            return@runCatching p
        }
        throw last ?: IllegalStateException("no port")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (e: Exception) { break }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 10_000
        val input = BufferedInputStream(c.getInputStream())
        val response = try {
            val requestLine = input.readLine() ?: return@use
            val parts = requestLine.split(' ')
            if (parts.size < 2) throw IllegalArgumentException("bad request line")
            val headers = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) throw IllegalArgumentException("body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val path = parts[1].substringBefore('?')
            runCatching { handler(HttpRequest(parts[0].uppercase(), path, headers, body, c.inetAddress.hostAddress ?: "")) }
                .getOrElse { HttpResponse(500, """{"error":"${(it.message ?: "error").replace("\"", "'")}"}""") }
        } catch (e: Exception) {
            HttpResponse(400, """{"error":"bad request"}""")
        }
        val bytes = response.body.toByteArray()
        val out = c.getOutputStream()
        out.write(
            ("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n" +
                "Content-Type: ${response.contentType}; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun InputStream.readLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (buf.size() > 8192) throw IllegalArgumentException("line too long")
            buf.write(b)
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; else -> "Error"
    }

    private companion object { const val MAX_BODY = 4 * 1024 * 1024 }
}
