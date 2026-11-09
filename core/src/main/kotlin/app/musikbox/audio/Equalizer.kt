package app.musikbox.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** The ten bands, one octave apart, the same on Android and desktop. */
val EQ_BANDS_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
const val EQ_MAX_DB = 12f
const val EQ_CUSTOM = "Custom"

data class EqPreset(val name: String, val gains: List<Float>)

val EQ_PRESETS = listOf(
    EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass boost", listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5f, 6f)),
    EqPreset("Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 3.5f, 3f, 1.5f, 0f, -1f)),
    EqPreset("Rock", listOf(4.5f, 3.5f, 2f, 0f, -1f, -1f, 0.5f, 2.5f, 3.5f, 4f)),
    EqPreset("Pop", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
    EqPreset("Jazz", listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1.5f, 2.5f, 3f)),
    EqPreset("Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqPreset("Electronic", listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1.5f, 4f, 5f)),
    EqPreset("Small speakers", listOf(-3f, -1f, 2f, 3f, 2f, 1f, 1f, 2f, 1f, 0f)),
)

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    val gains: List<Float> = List(EQ_BANDS_HZ.size) { 0f },
) {
    /** Whether it changes the sound at all: off, or on with every band at 0, does nothing. */
    val active: Boolean get() = enabled && gains.any { abs(it) >= 0.05f }

    /**
     * Boosting a band can push loud music past full scale, which distorts. So the whole signal comes
     * down by the biggest boost first: the shape is the same, it just plays a little quieter.
     */
    val preampDb: Float get() = -(gains.maxOrNull() ?: 0f).coerceAtLeast(0f)

    fun withPreset(p: EqPreset) = copy(preset = p.name, gains = p.gains)

    fun withBand(index: Int, db: Float): EqSettings {
        val g = gains.toMutableList()
        g[index] = (Math.round(db.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) * 2) / 2f)  // half-dB steps
        return copy(gains = g, preset = EQ_PRESETS.firstOrNull { it.gains == g }?.name ?: EQ_CUSTOM)
    }

    /** Anything stored that isn't ten sane bands is put right rather than trusted. */
    fun sanitized(): EqSettings {
        val g = List(EQ_BANDS_HZ.size) { i -> gains.getOrNull(i)?.takeIf { it.isFinite() }?.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) ?: 0f }
        return copy(gains = g)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String?): EqSettings =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.sanitized() ?: EqSettings()
    }
}

/** "1k", "16k", "62": what the band is called under its slider. */
fun eqBandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

/** The same equalizer as an mpv audio filter: one-octave peaking bands plus the headroom cut. Null when it does nothing. */
fun mpvAudioFilter(eq: EqSettings): String? {
    if (!eq.active) return null
    val parts = buildList {
        if (eq.preampDb < 0f) add("volume=${fmt(eq.preampDb)}dB")
        EQ_BANDS_HZ.zip(eq.gains).forEach { (hz, g) -> if (abs(g) >= 0.05f) add("equalizer=f=$hz:t=o:w=1:g=${fmt(g)}") }
    }
    return "lavfi=[${parts.joinToString(",")}]"
}

private fun fmt(v: Float) = String.format(Locale.ROOT, "%.1f", v)

/**
 * The equalizer in software, for players without an audio filter chain (Android's ExoPlayer).
 * One RBJ "peaking EQ" biquad per band, one octave wide, as in mpv's equalizer, so both apps sound alike.
 */
class EqFilter(private val sampleRate: Int, private val channels: Int, eq: EqSettings) {
    private class Band(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private val preamp = 10.0.pow(eq.preampDb / 20.0)
    private val bands: Array<Band> = EQ_BANDS_HZ.zip(eq.gains)
        .filter { (hz, g) -> abs(g) >= 0.05f && hz < sampleRate * 0.45 }  // nothing to do near or past Nyquist
        .map { (hz, g) -> band(hz.toDouble(), g.toDouble()) }
        .toTypedArray()
    // Per channel and band: the last two inputs and outputs (direct form I).
    private val state = Array(channels) { Array(bands.size) { DoubleArray(4) } }

    private fun band(f0: Double, gainDb: Double): Band {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f0 / sampleRate
        val octaves = 1.0
        val q = 1.0 / (2 * sinh(ln(2.0) / 2 * octaves * w0 / sin(w0)))
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        return Band(
            b0 = (1 + alpha * a) / a0, b1 = (-2 * cos(w0)) / a0, b2 = (1 - alpha * a) / a0,
            a1 = (-2 * cos(w0)) / a0, a2 = (1 - alpha / a) / a0,
        )
    }

    /** Filters interleaved samples in place. Values stay in -1..1 (clipped, should the headroom ever not be enough). */
    fun process(samples: FloatArray, count: Int = samples.size) {
        var i = 0
        while (i + channels <= count) {
            for (c in 0 until channels) {
                var x = samples[i + c] * preamp
                val s = state[c]
                for (b in bands.indices) {
                    val f = bands[b]
                    val z = s[b]
                    val y = f.b0 * x + f.b1 * z[0] + f.b2 * z[1] - f.a1 * z[2] - f.a2 * z[3]
                    z[1] = z[0]; z[0] = x; z[3] = z[2]; z[2] = y
                    x = y
                }
                samples[i + c] = x.coerceIn(-1.0, 1.0).toFloat()
            }
            i += channels
        }
    }

    fun reset() = state.forEach { ch -> ch.forEach { it.fill(0.0) } }
}

/**
 * The equalizer as the app holds it: changes apply at once (a slider drag updates the sound live),
 * and are saved a moment after the last one, not on every frame of a drag.
 */
class EqualizerController(
    initial: EqSettings,
    private val scope: CoroutineScope,
    private val save: suspend (EqSettings) -> Unit,
) {
    private val _state = MutableStateFlow(initial.sanitized())
    val state: StateFlow<EqSettings> = _state.asStateFlow()
    private var pending: Job? = null

    fun update(transform: (EqSettings) -> EqSettings) {
        val next = transform(_state.value).sanitized()
        _state.value = next
        pending?.cancel()
        pending = scope.launch { delay(SAVE_DELAY_MS); save(next) }
    }

    companion object { const val SAVE_DELAY_MS = 600L }
}
