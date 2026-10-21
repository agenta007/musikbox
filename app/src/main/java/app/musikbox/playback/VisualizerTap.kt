package app.musikbox.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives the exact PCM ExoPlayer is about to play (via [TeeAudioProcessor]) and keeps the
 * last ~0.7 s, mono-mixed, for the visualizer. Unlike android.media.audiofx.Visualizer this
 * needs no microphone permission. Copies nothing unless a visualizer is on screen.
 */
@OptIn(UnstableApi::class)
class VisualizerTap : TeeAudioProcessor.AudioBufferSink {
    private val ring = FloatArray(1 shl 15)
    private var written = 0L
    @Volatile var sampleRate = 44_100
        private set
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** Set while a visualizer is visible; otherwise the audio thread does no extra work. */
    @Volatile var active = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(ring) {
            sampleRate = sampleRateHz
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            written = 0
            ring.fill(0f)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!active) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        synchronized(ring) {
            val ch = channels
            when (encoding) {
                C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch) {
                    var sum = 0f
                    repeat(ch) { sum += b.short / 32768f }
                    ring[(written and MASK).toInt()] = sum / ch
                    written++
                }
                C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch) {
                    var sum = 0f
                    repeat(ch) { sum += b.float }
                    ring[(written and MASK).toInt()] = sum / ch
                    written++
                }
                else -> Unit
            }
        }
    }

    /**
     * Copy the [dest].size samples that are audible now: PCM reaches this tap before it has
     * gone through the AudioTrack buffer, so read [latencyMs] behind the newest sample.
     */
    fun read(dest: FloatArray, latencyMs: Int = 180): Boolean = synchronized(ring) {
        if (written < dest.size) return false
        var end = written - latencyMs.toLong() * sampleRate / 1000
        if (end < dest.size) end = written
        val start = end - dest.size
        for (i in dest.indices) dest[i] = ring[((start + i) and MASK).toInt()]
        true
    }

    private companion object { const val MASK = (1L shl 15) - 1 }
}
