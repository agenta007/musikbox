package app.musikbox.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app debug log for downloads and sync. Mirrored to logcat (tag `musikbox`), kept in memory
 * for the Downloads screen and persisted to a small file so it survives restarts.
 */
class ActivityLog(context: Context, private val scope: CoroutineScope) {

    enum class Level { Debug, Info, Warn, Error }

    data class Entry(val timeMs: Long, val level: Level, val message: String) {
        fun format(): String = "${stamp.get()!!.format(Date(timeMs))} ${level.name.first()} $message"
    }

    private val file = File(context.filesDir, "activity-log.txt")
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var saveJob: Job? = null

    init {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { file.readLines().mapNotNull(::parse) }.getOrDefault(emptyList()) }
            _entries.update { (loaded + it).takeLast(MAX) }
        }
    }

    fun d(message: String) = add(Level.Debug, message)
    fun i(message: String) = add(Level.Info, message)
    fun w(message: String) = add(Level.Warn, message)
    fun e(message: String) = add(Level.Error, message)

    private fun add(level: Level, message: String) {
        val priority = when (level) {
            Level.Debug -> Log.DEBUG
            Level.Info -> Log.INFO
            Level.Warn -> Log.WARN
            Level.Error -> Log.ERROR
        }
        Log.println(priority, TAG, message)
        _entries.update { (it + Entry(System.currentTimeMillis(), level, message)).takeLast(MAX) }
        scheduleSave()
    }

    fun clear() {
        _entries.value = emptyList()
        scheduleSave()
    }

    fun asText(includeDebug: Boolean = true): String =
        _entries.value.filter { includeDebug || it.level != Level.Debug }.joinToString("\n") { it.format() }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            val snapshot = _entries.value
            withContext(Dispatchers.IO) {
                runCatching {
                    file.writeText(snapshot.joinToString("\n") { "${it.timeMs}|${it.level.name}|${it.message.replace('\n', ' ')}" })
                }
            }
        }
    }

    private fun parse(line: String): Entry? {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val level = runCatching { Level.valueOf(parts[1]) }.getOrNull() ?: return null
        return Entry(time, level, parts[2])
    }

    private companion object {
        const val TAG = "musikbox"
        const val MAX = 600
        val stamp = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        }
    }
}
