package app.musikbox.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.musikbox.audio.EqFilter
import app.musikbox.audio.EqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The equalizer, inside ExoPlayer's audio chain. Android's own audiofx Equalizer depends on the
 * phone (often five bands, sometimes none); this gives every phone the same ten bands as the desktop
 * app. It reads [settings] on every buffer, so a slider moves the sound while it's dragged.
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor(private val settings: () -> EqSettings) : BaseAudioProcessor() {
    private var filter: EqFilter? = null
    private var filterFor: EqSettings? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val eq = settings()
        if (!eq.active) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val format = inputAudioFormat
        if (filter == null || filterFor != eq) {
            filter = EqFilter(format.sampleRate, format.channelCount, eq)
            filterFor = eq
        }
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val count = if (float) size / 4 else size / 2
        if (scratch.size < count) scratch = FloatArray(count)
        if (float) {
            for (i in 0 until count) scratch[i] = input.float
        } else {
            for (i in 0 until count) scratch[i] = input.short / 32768f
        }
        filter!!.process(scratch, count)
        out.order(ByteOrder.nativeOrder())
        if (float) {
            for (i in 0 until count) out.putFloat(scratch[i])
        } else {
            for (i in 0 until count) out.putShort((scratch[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush() {
        filter?.reset()  // a seek: the old samples' echo in the filter state shouldn't bleed in
    }

    override fun onReset() {
        filter = null
        filterFor = null
        scratch = FloatArray(0)
    }
}
