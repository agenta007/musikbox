package app.musikbox.desktop.player

import app.musikbox.desktop.AppPaths
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

sealed interface MpvEvent {
    data class Property(val name: String, val value: JsonElement) : MpvEvent
    data class EndFile(val reason: String, val error: String?) : MpvEvent
    data object FileLoaded : MpvEvent
    data object Died : MpvEvent
}

/** mpv's JSON IPC connection: a Unix socket on Linux, a named pipe on Windows. */
private interface IpcChannel {
    val isOpen: Boolean
    /** Bytes read into [buf], or -1 once mpv has closed its end. */
    fun read(buf: ByteBuffer): Int
    fun write(buf: ByteBuffer)
    fun close()
}

private class UnixIpc(path: File) : IpcChannel {
    private val ch = SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path.toPath())) }
    override val isOpen get() = ch.isConnected
    override fun read(buf: ByteBuffer) = ch.read(buf)
    override fun write(buf: ByteBuffer) { while (buf.hasRemaining()) ch.write(buf) }
    override fun close() = ch.close()
}

/**
 * A plain blocking handle on a pipe serializes I/O, so a read waiting for mpv's next event would
 * hold every command back. AsynchronousFileChannel opens the pipe for overlapped I/O, which lets the
 * reader thread and command writes run at the same time; the position argument is ignored for pipes.
 */
private class PipeIpc(name: String) : IpcChannel {
    private val ch = AsynchronousFileChannel.open(Path.of(name), StandardOpenOption.READ, StandardOpenOption.WRITE)
    override val isOpen get() = ch.isOpen
    override fun read(buf: ByteBuffer): Int = try {
        ch.read(buf, 0).get()
    } catch (e: ExecutionException) {
        -1 // ERROR_BROKEN_PIPE: mpv is gone
    }
    override fun write(buf: ByteBuffer) {
        try {
            while (buf.hasRemaining()) ch.write(buf, 0).get()
        } catch (e: ExecutionException) {
            throw IOException(e.cause)
        }
    }
    override fun close() = ch.close()
}

/**
 * A headless mpv process driven over its JSON IPC socket. mpv does the decoding (every
 * format, network buffering, ReplayGain) and audio output via PipeWire/Pulse/ALSA.
 *
 * Started with `--no-config` so a user's mpv.conf or scripts (e.g. mpv-mpris, which would
 * duplicate our own MPRIS player) can't interfere.
 */
class Mpv {
    @Volatile private var process: Process? = null
    private var hookInstalled = false
    private var channel: IpcChannel? = null
    private val writeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<MpvEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<MpvEvent> = _events

    val isRunning get() = process?.isAlive == true && channel?.isOpen == true

    fun start(volume: Int): Result<Unit> = runCatching {
        if (isRunning) return@runCatching
        val exe = findExecutable("mpv") ?: throw IOException(
            if (AppPaths.isWindows) "mpv is not installed. Install it (e.g. `scoop install extras/mpv`) and make sure mpv.exe is on PATH, or set MUSIKBOX_MPV to its full path."
            else "mpv is not installed. Install it with your package manager (e.g. `sudo pacman -S mpv`).",
        )
        val pid = ProcessHandle.current().pid()
        sweepStaleFiles()
        val socket = File(AppPaths.runtime, "musikbox-mpv-$pid.sock")
        val pipe = "\\\\.\\pipe\\musikbox-mpv-$pid"
        socket.delete()
        val args = mutableListOf(
            exe,
            "--idle=yes", "--no-config", "--load-scripts=no", "--no-video", "--audio-display=no",
            "--no-terminal", "--input-ipc-server=${if (AppPaths.isWindows) pipe else socket.absolutePath}",
            "--volume=$volume", "--volume-max=100",
            "--cache=yes", "--demuxer-max-bytes=64MiB", "--demuxer-readahead-secs=60",
            "--audio-client-name=musikbox", "--title=musikbox",
        )
        if (!AppPaths.isWindows) {
            // mpv must never outlive the app, even if the JVM is SIGKILLed or crashes:
            // a tiny Lua timer inside mpv quits it once our PID is gone. (Windows has no /proc;
            // there the shutdown hook below is all there is.)
            val watchdog = File(AppPaths.runtime, "musikbox-watchdog-$pid.lua").apply {
                writeText(
                    """
                    local function check()
                        local f = io.open("/proc/$pid/stat", "r")
                        if f then f:close() else mp.command("quit") end
                    end
                    mp.add_periodic_timer(2, check)
                    """.trimIndent(),
                )
            }
            args += "--script=${watchdog.absolutePath}"
        }
        // Extra options, e.g. MUSIKBOX_MPV_OPTS="--audio-device=pipewire/alsa_output.usb-…"
        args += System.getenv("MUSIKBOX_MPV_OPTS").orEmpty().split(' ').filter { it.isNotBlank() }
        process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!hookInstalled) {
            hookInstalled = true
            Runtime.getRuntime().addShutdownHook(Thread { process?.destroyForcibly() })
        }

        val deadline = System.currentTimeMillis() + 5_000
        var ch: IpcChannel? = null
        while (ch == null) {
            if (process?.isAlive != true) throw IOException("mpv exited during startup")
            ch = when {
                AppPaths.isWindows -> runCatching { PipeIpc(pipe) }.getOrNull()
                socket.exists() -> runCatching { UnixIpc(socket) }.getOrNull()
                else -> null
            }
            if (ch == null) {
                if (System.currentTimeMillis() > deadline) throw IOException("mpv didn't open its control socket")
                Thread.sleep(50)
            }
        }
        channel = ch
        Thread({ readLoop(ch) }, "mpv-ipc").apply { isDaemon = true }.start()

        listOf("pause", "time-pos", "duration", "idle-active", "paused-for-cache", "volume")
            .forEachIndexed { i, name -> command("observe_property", i + 1, name) }
    }

    private fun readLoop(ch: IpcChannel) {
        val buf = ByteBuffer.allocate(64 * 1024)
        val line = ByteArrayOutputStream()
        try {
            while (true) {
                buf.clear()
                if (ch.read(buf) < 0) break
                buf.flip()
                while (buf.hasRemaining()) {
                    val b = buf.get()
                    if (b == '\n'.code.toByte()) {
                        handle(line.toString(Charsets.UTF_8))
                        line.reset()
                    } else line.write(b.toInt())
                }
            }
        } catch (_: IOException) {
        }
        _events.tryEmit(MpvEvent.Died)
    }

    private fun handle(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["event"]?.jsonPrimitive?.content) {
            "property-change" -> {
                val name = obj["name"]?.jsonPrimitive?.content ?: return
                _events.tryEmit(MpvEvent.Property(name, obj["data"] ?: JsonNull))
            }
            "end-file" -> _events.tryEmit(
                MpvEvent.EndFile(
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "unknown",
                    error = obj["file_error"]?.jsonPrimitive?.content,
                ),
            )
            "file-loaded" -> _events.tryEmit(MpvEvent.FileLoaded)
        }
    }

    /** Removes sockets/watchdogs left by instances that were killed. */
    private fun sweepStaleFiles() {
        val pattern = Regex("""musikbox-(?:mpv|watchdog)-(\d+)\.(?:sock|lua)""")
        AppPaths.runtime.listFiles()?.forEach { f ->
            val owner = pattern.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
            if (ProcessHandle.of(owner).isEmpty) f.delete()
        }
    }

    fun command(vararg args: Any?) {
        val ch = channel ?: return
        val msg = buildJsonObject {
            put("command", buildJsonArray { args.forEach { add(it.toJson()) } })
        }.toString() + "\n"
        synchronized(writeLock) {
            runCatching { ch.write(ByteBuffer.wrap(msg.toByteArray(Charsets.UTF_8))) }
        }
    }

    fun setProperty(name: String, value: Any?) = command("set_property", name, value)

    fun stop() {
        command("quit")
        process?.let { p ->
            if (!p.waitFor(800, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        runCatching { channel?.close() }
        process = null
        channel = null
        val pid = ProcessHandle.current().pid()
        File(AppPaths.runtime, "musikbox-mpv-$pid.sock").delete()
        File(AppPaths.runtime, "musikbox-watchdog-$pid.lua").delete()
    }

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    companion object {
        /** [name] on PATH (as name.exe on Windows); for mpv, MUSIKBOX_MPV overrides the lookup. */
        fun findExecutable(name: String): String? {
            if (name == "mpv") System.getenv("MUSIKBOX_MPV")?.let(::File)?.takeIf { it.isFile }?.let { return it.absolutePath }
            val file = if (AppPaths.isWindows) "$name.exe" else name
            return System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it.trim('"'), file) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
        }

        fun version(): String? = runCatching {
            val exe = findExecutable("mpv") ?: return null
            ProcessBuilder(exe, "--version").start().inputStream.bufferedReader().readLine()
                ?.substringBefore(" Copyright")
        }.getOrNull()
    }
}

internal val JsonElement.asDouble: Double? get() = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
internal val JsonElement.asBool: Boolean? get() = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

