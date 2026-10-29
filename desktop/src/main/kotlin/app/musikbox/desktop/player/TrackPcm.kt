package app.musikbox.desktop.player

import app.musikbox.data.remote.SubsonicClient
import app.musikbox.desktop.AppPaths
import app.musikbox.desktop.data.Downloads
import app.musikbox.desktop.data.SettingsRepo
import app.musikbox.desktop.data.StreamCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * mpv plays audio itself and offers no PCM tap, so for the visualizer we let mpv decode a copy
 * of the current track to 22 kHz mono 16-bit (≈0.1 s for a typical song, no extra dependency)
 * and look samples up by playback position.
 */
class TrackPcm(
    private val api: SubsonicClient,
    private val downloads: Downloads,
    private val cache: StreamCache,
    private val settings: SettingsRepo,
    private val scope: CoroutineScope,
) {
    class Pcm(val songId: String, val rate: Int, val samples: ShortArray)

    private val _current = MutableStateFlow<Pcm?>(null)
    val current: StateFlow<Pcm?> = _current.asStateFlow()
    private var job: Job? = null
    @Volatile private var loadingId: String? = null

    fun ensure(songId: String?) {
        if (songId == null || _current.value?.songId == songId || loadingId == songId) return
        loadingId = songId
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val pcm = runCatching { decode(songId) }.getOrNull()
            if (loadingId == songId) {
                _current.value = pcm?.let { Pcm(songId, RATE, it) }
                loadingId = null
            }
        }
    }

    private fun decode(id: String): ShortArray? {
        val br = settings.current.streamBitrate
        val source = downloads.file(id)?.absolutePath ?: cache.file(id, br)?.absolutePath ?: api.streamUrl(id, br) ?: return null
        val mpv = Mpv.findExecutable("mpv") ?: return null
        // Windows has no /dev/stdout, so mpv writes a temporary file there instead.
        val tmp = if (AppPaths.isWindows) File.createTempFile("musikbox-pcm-", ".raw", AppPaths.cache) else null
        val bytes = try {
            val proc = ProcessBuilder(
                mpv, "--no-config", "--really-quiet", "--no-video", "--untimed", "--length=$MAX_SECONDS",
                "--ao=pcm", "--ao-pcm-waveheader=no", "--ao-pcm-file=${tmp?.absolutePath ?: "/dev/stdout"}",
                "--audio-channels=mono", "--audio-samplerate=$RATE", "--audio-format=s16", source,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = ByteArrayOutputStream(1 shl 20).also { out -> proc.inputStream.use { it.copyTo(out) } }.toByteArray()
            proc.waitFor()
            tmp?.readBytes() ?: out
        } finally {
            tmp?.delete()
        }
        if (bytes.size < 4) return null
        return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** Fill [dest] with the samples just before [positionMs]; false if not available. */
    fun window(pcm: Pcm, positionMs: Long, dest: FloatArray): Boolean {
        val end = (positionMs * pcm.rate / 1000).toInt()
        val start = end - dest.size
        if (start < 0 || end > pcm.samples.size) return false
        for (i in dest.indices) dest[i] = pcm.samples[start + i] / 32768f
        return true
    }

    companion object {
        const val RATE = 22_050
        private const val MAX_SECONDS = 1800 // longer tracks: the first 30 min are visualised
    }
}
