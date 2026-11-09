package app.musikbox

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.musikbox.audio.EqSettings
import app.musikbox.playback.EqualizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Real PCM through the processor exactly as ExoPlayer feeds it: configure, queue, read back. */
class EqualizerProcessorTest {
    private val rate = 44_100

    private fun run(eq: () -> EqSettings, encoding: Int, hz: Double): Pair<DoubleArray, DoubleArray> {
        val processor = EqualizerProcessor(eq)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, encoding))
        processor.flush()
        val frames = rate
        val input = DoubleArray(frames) { 0.25 * sin(2 * PI * hz * it / rate) }
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val buffer = ByteBuffer.allocateDirect(frames * 2 * bytesPerSample).order(ByteOrder.nativeOrder())
        input.forEach { v ->
            repeat(2) {
                if (encoding == C.ENCODING_PCM_FLOAT) buffer.putFloat(v.toFloat()) else buffer.putShort((v * 32767).toInt().toShort())
            }
        }
        buffer.flip()
        val out = DoubleArray(frames)
        var frame = 0
        // ExoPlayer hands audio over in chunks; so does this test.
        while (buffer.hasRemaining()) {
            val chunk = buffer.slice().order(ByteOrder.nativeOrder()).limit(minOf(4096, buffer.remaining())) as ByteBuffer
            buffer.position(buffer.position() + chunk.remaining())
            processor.queueInput(chunk)
            val o = processor.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) {
                val left = if (encoding == C.ENCODING_PCM_FLOAT) o.float.toDouble() else o.short / 32768.0
                if (encoding == C.ENCODING_PCM_FLOAT) o.float else o.short  // right channel
                out[frame++] = left
            }
        }
        assertEquals(frames, frame)
        return input to out
    }

    private fun gain(eq: EqSettings, encoding: Int, hz: Double): Double =
        run({ eq }, encoding, hz).let { (input, output) -> gainDb(input, output) }

    private fun gainDb(input: DoubleArray, output: DoubleArray): Double {
        fun rms(a: DoubleArray) = sqrt(a.drop(a.size / 2).sumOf { it * it } / (a.size / 2))
        return 20 * log10(rms(output) / rms(input))
    }

    @Test fun offPassesAudioThroughUntouched() {
        val (input, output) = run({ EqSettings(enabled = false) }, C.ENCODING_PCM_16BIT, 1000.0)
        assertEquals(0.0, gainDb(input, output), 0.01)
    }

    @Test fun sixteenBitAudioGetsTheEqualizer() {
        val eq = EqSettings(enabled = true).withBand(5, -9f)  // 1 kHz down 9 dB
        assertEquals(-9.0, gain(eq, C.ENCODING_PCM_16BIT, 1000.0), 0.5)
        assertEquals(0.0, gain(eq, C.ENCODING_PCM_16BIT, 8000.0), 0.4)
    }

    @Test fun floatAudioGetsTheEqualizer() {
        val eq = EqSettings(enabled = true).withBand(2, 6f)  // 125 Hz up 6 dB, less 6 dB headroom
        assertEquals(0.0, gain(eq, C.ENCODING_PCM_FLOAT, 125.0), 0.4)
        assertEquals(-6.0, gain(eq, C.ENCODING_PCM_FLOAT, 4000.0), 0.4)
    }

    @Test fun aChangeTakesEffectMidStream() {
        var eq = EqSettings(enabled = false)
        val processor = EqualizerProcessor { eq }
        processor.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        fun level(): Double {
            val b = ByteBuffer.allocateDirect(rate * 2).order(ByteOrder.nativeOrder())
            repeat(rate) { b.putShort((8000 * sin(2 * PI * 1000.0 * it / rate)).toInt().toShort()) }
            b.flip()
            processor.queueInput(b)
            val o = processor.output.order(ByteOrder.nativeOrder())
            var sum = 0.0; var n = 0
            while (o.hasRemaining()) { val s = o.short.toDouble(); if (n++ > rate / 2) sum += s * s }
            return sqrt(sum / (rate / 2))
        }
        val before = level()
        eq = EqSettings(enabled = true).withBand(5, -12f)
        val after = level()
        assertTrue("expected the cut to apply at once, got $before -> $after", after < before / 3)
    }
}
