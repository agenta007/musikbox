package app.musikbox

import app.musikbox.visualizer.SpectrumAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpectrumTest {
    private fun sine(hz: Double, rate: Int, n: Int, amp: Double = 0.8) = FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test fun fftFindsTheRightBin() {
        val n = 1024; val rate = 8192
        val re = DoubleArray(n) { sin(2 * PI * 512.0 * it / rate) }   // exactly bin 64
        val im = DoubleArray(n)
        SpectrumAnalyzer.fft(re, im)
        val mags = (0 until n / 2).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxBy { mags[it] })
    }

    @Test fun toneLightsUpItsBandOnly() {
        val rate = 44_100
        val a = SpectrumAnalyzer(bands = 48)
        repeat(30) { a.update(sine(1000.0, rate, a.windowSize), rate, 16f) }
        val hot = a.levels.indices.maxBy { a.levels[it] }
        val center = a.bandCenterHz(hot, rate)
        assertTrue("1 kHz lands in a band near 1 kHz (got $center)", abs(center - 1000) < 200)
        assertTrue(a.levels[hot] > 0.8f)
        // Far-away bands stay low.
        assertTrue(a.levels.filterIndexed { i, _ -> abs(i - hot) > 6 }.all { it < 0.35f })
    }

    @Test fun barsAndPeaksFallToRestWhenStopped() {
        val rate = 44_100
        val a = SpectrumAnalyzer()
        repeat(20) { a.update(sine(200.0, rate, a.windowSize), rate, 16f) }
        assertTrue(a.peaks.max() > 0.5f)
        repeat(200) { a.update(null, rate, 16f) }
        assertTrue(a.levels.all { it == 0f })
        assertTrue(a.peaks.all { it < 0.01f })
    }

    @Test fun quietMasterStillMoves() {
        val rate = 11_025
        val a = SpectrumAnalyzer()
        repeat(150) { a.update(sine(270.0, rate, a.windowSize, amp = 0.04), rate, 16f) }   // like the test library: -28 dBFS
        assertTrue("auto-gain lifts quiet audio (max ${a.levels.max()})", a.levels.max() > 0.6f)
    }
}
