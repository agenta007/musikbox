package app.musikbox.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class VisualizerStyle(val label: String) { Off("Off"), Bars("Bars"), Mirror("Mirror"), Radial("Radial") }

/**
 * Turns raw PCM into the bar heights of an audioMotion/Feishin-style spectrum: Hann-windowed
 * FFT → log-spaced bands (equal width per octave, like the ear) → dB scale → attack/release
 * smoothing with falling peak caps. Pure Kotlin, shared by both apps.
 *
 * Not thread-safe: drive it from the render loop.
 */
class SpectrumAnalyzer(
    val bands: Int = 48,
    private val fftSize: Int = 2048,
    private val minHz: Double = 32.0,
    private val maxHz: Double = 16_000.0,
    /** dBFS range mapped to 0..1. */
    private val floorDb: Double = -70.0,
    private val ceilDb: Double = -12.0,
) {
    init { require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" } }

    /** Current bar heights, 0..1. */
    val levels = FloatArray(bands)
    /** Peak caps, 0..1 — they hold briefly, then fall. */
    val peaks = FloatArray(bands)

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val raw = FloatArray(bands)
    private val peakHold = FloatArray(bands)
    private var gainDb = 0.0
    private var lastRate = 0
    private var bandLo = IntArray(bands)
    private var bandHi = IntArray(bands)

    val windowSize get() = fftSize

    /**
     * Analyse [samples] (mono, -1..1, the most recent [windowSize] at the playhead) and advance
     * the animation by [dtMs]. Pass null when nothing is playing: bars and peaks fall to rest.
     */
    fun update(samples: FloatArray?, sampleRate: Int, dtMs: Float) {
        if (samples == null) {
            decayAll(dtMs)
            return
        }
        if (sampleRate != lastRate) mapBands(sampleRate)
        val n = min(samples.size, fftSize)
        val offset = samples.size - n
        for (i in 0 until fftSize) {
            re[i] = if (i < n) samples[offset + i] * window[i] else 0.0
            im[i] = 0.0
        }
        fft(re, im)
        // Coherent gain of the Hann window is 0.5; normalise so a full-scale sine reads ~0 dBFS.
        val norm = 2.0 / (fftSize * 0.5)
        var loudest = floorDb
        for (b in 0 until bands) {
            var power = 0.0
            for (k in bandLo[b]..bandHi[b]) power = max(power, re[k] * re[k] + im[k] * im[k])
            val db = 10 * log10(power * norm * norm + 1e-12)
            loudest = max(loudest, db)
            raw[b] = db.toFloat()
        }
        // Gentle auto-gain: quiet masters still fill the display, loud ones don't clip it.
        val target = (ceilDb - loudest).coerceIn(-6.0, 24.0)
        gainDb += (target - gainDb) * min(1.0, dtMs / 1500.0)
        for (b in 0 until bands) {
            val v = (((raw[b] + gainDb) - floorDb) / (ceilDb - floorDb)).toFloat().coerceIn(0f, 1f)
            // Fast attack, slower release.
            val k = if (v > levels[b]) min(1f, dtMs / 25f) else min(1f, dtMs / 180f)
            levels[b] += (v - levels[b]) * k
        }
        updatePeaks(dtMs)
    }

    /** Every bar and peak cap has fallen to zero: nothing left to animate. */
    val isSilent: Boolean get() = levels.all { it == 0f } && peaks.all { it == 0f }

    private fun decayAll(dtMs: Float) {
        for (b in 0 until bands) levels[b] = max(0f, levels[b] - dtMs / 400f)
        updatePeaks(dtMs)
    }

    private fun updatePeaks(dtMs: Float) {
        for (b in 0 until bands) {
            if (levels[b] >= peaks[b]) {
                peaks[b] = levels[b]; peakHold[b] = 350f
            } else if (peakHold[b] > 0f) {
                peakHold[b] -= dtMs
            } else {
                peaks[b] = max(levels[b], peaks[b] - dtMs / 900f)
            }
        }
    }

    /** Log-spaced band edges; every band covers at least one FFT bin. */
    private fun mapBands(sampleRate: Int) {
        lastRate = sampleRate
        val nyquist = sampleRate / 2.0
        val top = min(maxHz, nyquist * 0.98)
        val binHz = sampleRate.toDouble() / fftSize
        val ratio = ln(top / minHz)
        var prevHi = 0
        for (b in 0 until bands) {
            val f0 = minHz * kotlin.math.exp(ratio * b / bands)
            val f1 = minHz * kotlin.math.exp(ratio * (b + 1) / bands)
            val lo = max(prevHi + 1, (f0 / binHz).toInt()).coerceAtMost(fftSize / 2 - 1)
            val hi = max(lo, (f1 / binHz).toInt()).coerceAtMost(fftSize / 2 - 1)
            bandLo[b] = lo; bandHi[b] = hi; prevHi = hi
        }
    }

    /** Centre frequency of band [b] for the current sample rate (for tests/labels). */
    fun bandCenterHz(b: Int, sampleRate: Int): Double {
        if (sampleRate != lastRate) mapBands(sampleRate)
        val binHz = sampleRate.toDouble() / fftSize
        return (bandLo[b] + bandHi[b]) / 2.0 * binHz
    }

    companion object {
        /** In-place iterative radix-2 Cooley–Tukey FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
