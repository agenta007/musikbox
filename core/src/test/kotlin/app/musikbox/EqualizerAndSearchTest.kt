package app.musikbox

import app.musikbox.audio.EQ_PRESETS
import app.musikbox.audio.EqFilter
import app.musikbox.audio.EqSettings
import app.musikbox.audio.EqualizerController
import app.musikbox.audio.eqBandLabel
import app.musikbox.audio.mpvAudioFilter
import app.musikbox.data.matchesSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerAndSearchTest {

    // ---- the filter itself

    /** Level change in dB of a sine at [hz] going through the equalizer. */
    private fun responseDb(eq: EqSettings, hz: Double, rate: Int = 44_100): Double {
        val n = rate  // one second; the first half settles the filter
        val input = FloatArray(n) { (0.25 * sin(2 * PI * hz * it / rate)).toFloat() }
        val output = input.copyOf()
        EqFilter(rate, 1, eq).process(output)
        fun rms(a: FloatArray) = sqrt(a.drop(n / 2).sumOf { (it * it).toDouble() } / (n / 2))
        return 20 * log10(rms(output) / rms(input))
    }

    @Test fun aBoostedBandRisesByItsGainLessTheHeadroom() {
        val eq = EqSettings(enabled = true).withBand(5, 6f)  // +6 dB at 1 kHz, so the preamp is -6 dB
        assertEquals(0.0, responseDb(eq, 1000.0), 0.3)
        assertEquals(-6.0, responseDb(eq, 62.0), 0.5)  // far from the band: only the headroom cut
    }

    @Test fun aCutBandFallsByItsGain() {
        val eq = EqSettings(enabled = true).withBand(3, -9f)
        assertEquals(-9.0, responseDb(eq, 250.0), 0.4)
        assertEquals(0.0, responseDb(eq, 8000.0), 0.3)
    }

    @Test fun offOrFlatChangesNothing() {
        assertFalse(EqSettings(enabled = false, gains = EQ_PRESETS[1].gains).active)
        assertFalse(EqSettings(enabled = true).active)
        assertEquals(0.0, responseDb(EqSettings(enabled = true), 440.0), 0.01)
    }

    @Test fun neverPastFullScale() {
        val eq = EqSettings(enabled = true, gains = List(10) { 12f })
        val loud = FloatArray(44_100) { (0.99 * sin(2 * PI * 100 * it / 44_100)).toFloat() }
        EqFilter(44_100, 1, eq).process(loud)
        assertTrue(loud.all { it in -1f..1f })
    }

    @Test fun stereoChannelsAreFilteredSeparately() {
        val eq = EqSettings(enabled = true).withBand(0, 12f)
        val frames = FloatArray(2000) { if (it % 2 == 0) 0.1f else 0f }  // left DC-ish, right silent
        EqFilter(44_100, 2, eq).process(frames)
        assertTrue((1 until frames.size step 2).all { frames[it] == 0f })
    }

    @Test fun bandsPastNyquistAreSkipped() {
        val eq = EqSettings(enabled = true).withBand(9, 12f)  // 16 kHz, at a 22.05 kHz sample rate
        assertEquals(-12.0, responseDb(eq, 1000.0, rate = 22_050), 0.3)  // only the headroom cut remains
    }

    // ---- settings

    @Test fun movingASliderMakesItCustomUnlessItLandsOnAPreset() {
        val rock = EqSettings(enabled = true).withPreset(EQ_PRESETS.first { it.name == "Rock" })
        assertEquals("Custom", rock.withBand(2, 7f).preset)
        assertEquals("Rock", rock.withBand(2, 7f).withBand(2, 2f).preset)
        assertEquals(12f, rock.withBand(0, 40f).gains[0])
        assertEquals(2.5f, rock.withBand(0, 2.6f).gains[0])  // half-dB steps
    }

    @Test fun storedSettingsSurviveARoundTripAndBadDataIsRepaired() {
        val eq = EqSettings(enabled = true).withPreset(EQ_PRESETS[4])
        assertEquals(eq, EqSettings.decode(eq.encode()))
        assertEquals(EqSettings(), EqSettings.decode("not json"))
        assertEquals(10, EqSettings.decode("""{"enabled":true,"gains":[99,1]}""").gains.size)
        assertEquals(12f, EqSettings.decode("""{"enabled":true,"gains":[99,1]}""").gains[0])
    }

    @Test fun theMpvFilterListsOnlyBandsThatDoSomething() {
        assertNull(mpvAudioFilter(EqSettings(enabled = false, gains = List(10) { 3f })))
        val eq = EqSettings(enabled = true).withBand(1, 6f).withBand(5, -3.5f)
        assertEquals(
            "lavfi=[volume=-6.0dB,equalizer=f=62:t=o:w=1:g=6.0,equalizer=f=1000:t=o:w=1:g=-3.5]",
            mpvAudioFilter(eq),
        )
        assertEquals(listOf("31", "1k", "16k"), listOf(31, 1000, 16000).map(::eqBandLabel))
    }

    @Test fun theControllerAppliesAtOnceAndSavesOnceAfterADrag() = runBlocking {
        val saved = mutableListOf<EqSettings>()
        val c = EqualizerController(EqSettings(), CoroutineScope(Dispatchers.Default)) { saved += it }
        repeat(20) { i -> c.update { it.copy(enabled = true).withBand(0, i / 2f) } }
        assertEquals(9.5f, c.state.value.gains[0])
        delay(EqualizerController.SAVE_DELAY_MS + 400)
        assertEquals(1, saved.size)
        assertEquals(9.5f, saved.single().gains[0])
    }

    // ---- search in a playlist

    @Test fun searchMatchesEveryWordAcrossFields() {
        assertTrue(matchesSearch("bjork joga", "Jóga", "Björk", "Homogenic"))
        assertTrue(matchesSearch("HOMOG", "Jóga", "Björk", "Homogenic"))
        assertFalse(matchesSearch("bjork army", "Jóga", "Björk", "Homogenic"))
        assertTrue(matchesSearch("  ", "anything"))
        assertTrue(matchesSearch("ivo", "Пушкин", "Ivo Dimchev", null))
        assertTrue(matchesSearch("пушкин", "Пушкин", "Ivo Dimchev", null))
    }
}
