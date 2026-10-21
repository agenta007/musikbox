package app.musikbox.playback

import android.content.Context
import app.musikbox.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class QueueSnapshot(
    val songs: List<SongEntity>,
    val index: Int,
    val positionMs: Long,
    val playlistId: String? = null,
)

/** Persists the play queue so a cold start (or the system's resume button) picks up where you left off. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(snapshot: QueueSnapshot?) = withContext(Dispatchers.IO) {
        if (snapshot == null) { file.delete(); return@withContext }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        tmp.renameTo(file)
    }

    suspend fun load(): QueueSnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<QueueSnapshot>(file.readText()) }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() }
    }
}
