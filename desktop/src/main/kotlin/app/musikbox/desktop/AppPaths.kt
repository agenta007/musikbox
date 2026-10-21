package app.musikbox.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** XDG base directories: settings in ~/.config, library + downloads in ~/.local/share, audio/art in ~/.cache. */
object AppPaths {
    private fun base(env: String, fallback: String) =
        File(System.getenv(env)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + fallback))

    val config = File(base("XDG_CONFIG_HOME", "/.config"), "musikbox").apply { mkdirs() }
    val data = File(base("XDG_DATA_HOME", "/.local/share"), "musikbox").apply { mkdirs() }
    val cache = File(base("XDG_CACHE_HOME", "/.cache"), "musikbox").apply { mkdirs() }
    val runtime: File = System.getenv("XDG_RUNTIME_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: cache

    val settingsFile = File(config, "settings.json")
    val libraryFile = File(data, "library.json")
    val queueFile = File(data, "queue.json")
    val downloadsDir = File(data, "downloads").apply { mkdirs() }
    val audioCacheDir = File(cache, "audio").apply { mkdirs() }
    val artworkDir = File(cache, "artwork").apply { mkdirs() }

    val version: String = System.getProperty("musikbox.version") ?: "dev"
}

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** JSON persisted with write-to-temp + atomic rename, so a crash never leaves a half-written file. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val private: Boolean = false) {
    fun load(): T? = runCatching { if (file.exists()) appJson.decodeFromString(serializer, file.readText()) else null }.getOrNull()

    suspend fun save(value: T?) = withContext(Dispatchers.IO) { saveBlocking(value) }

    @Synchronized
    fun saveBlocking(value: T?) {
        if (value == null) { file.delete(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(appJson.encodeToString(serializer, value))
        if (private) runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
