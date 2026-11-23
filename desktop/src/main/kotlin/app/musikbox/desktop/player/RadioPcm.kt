package app.musikbox.desktop.player

import app.musikbox.desktop.AppPaths
import java.io.InputStream

/**
 * mpv plays the radio itself and offers no PCM tap, so for the station's live waveform a second,
 * quiet mpv decodes the same stream to 11 kHz mono 16-bit on stdout. Both connect at the same
 * moment and Icecast servers start both at the same point, so sample n of this copy is heard at
 * playback position n / [RATE]; [window] looks samples up by mpv's position like [TrackPcm].
 * Costs a second connection to the station (a 64–128 kbit/s stream) while radio plays.
 * Not on Windows, which has no /dev/stdout: the wave rests there.
 */
class RadioPcm {
    private val ring = ShortArray(RATE * 30)
    @Volatile private var written = 0L
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0

    @Synchronized
    fun start(url: String) {
        stop()
        if (AppPaths.isWindows) return
        val mpv = Mpv.findExecutable("mpv") ?: return
        val gen = ++generation
        written = 0
        val proc = runCatching {
            ProcessBuilder(
                mpv, "--no-config", "--really-quiet", "--no-video", "--load-scripts=no",
                "--ao=pcm", "--ao-pcm-waveheader=no", "--ao-pcm-file=/dev/stdout",
                "--audio-channels=mono", "--audio-samplerate=$RATE", "--audio-format=s16", url,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return
        process = proc
        Thread({ pump(proc.inputStream, gen) }, "radio-pcm").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        generation++
        process?.destroy()
        process = null
        written = 0
    }

    private fun pump(input: InputStream, gen: Int) {
        val buf = ByteArray(8192)
        var odd = -1 // a byte left over from the last read
        try {
            input.use { s ->
                while (gen == generation) {
                    val n = s.read(buf)
                    if (n < 0) break
                    var i = 0
                    synchronized(ring) {
                        if (odd >= 0 && n > 0) {
                            put(((odd and 0xff) or (buf[0].toInt() shl 8)).toShort()); i = 1; odd = -1
                        }
                        while (i + 1 < n) {
                            put(((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort()); i += 2
                        }
                        if (i < n) odd = buf[i].toInt()
                    }
                }
            }
        } catch (_: Exception) {
            // the station or the process went away; the wave rests
        }
    }

    private fun put(v: Short) {
        ring[(written % ring.size).toInt()] = v
        written++
    }

    /**
     * Fill [dest] with the samples heard at [positionMs]. When the position doesn't line up
     * (HLS streams count from elsewhere), the newest samples, a little behind, will do.
     */
    fun window(positionMs: Long, dest: FloatArray): Boolean = synchronized(ring) {
        val have = written
        if (have < dest.size) return false
        val oldest = maxOf(0L, have - ring.size)
        var end = positionMs * RATE / 1000
        if (end > have || end - dest.size < oldest) end = have - RATE / 4
        if (end - dest.size < oldest) return false
        val start = end - dest.size
        for (i in dest.indices) dest[i] = ring[((start + i) % ring.size).toInt()] / 32768f
        true
    }

    companion object {
        const val RATE = 11_025
    }
}
