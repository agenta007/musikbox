package app.musikbox.lyrics

import app.musikbox.data.remote.StructuredLyricsDto
import kotlinx.serialization.Serializable

@Serializable
enum class LyricsSource { Server, Lrclib }

/** One lyric line; [startMs] is null for plain (unsynced) lyrics. */
@Serializable
data class LyricLine(val startMs: Long? = null, val text: String)

@Serializable
data class Lyrics(
    val source: LyricsSource,
    val synced: Boolean,
    val lines: List<LyricLine>,
    val instrumental: Boolean = false,
) {
    /** Index of the line being sung at [positionMs], or -1 before the first one (and for plain lyrics). */
    fun lineAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if ((lines[mid].startMs ?: 0) <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/** Result of a lookup; [Failed] means "couldn't ask", as opposed to "nobody has lyrics for this". */
sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object None : LyricsResult
    data class Failed(val message: String) : LyricsResult
}

object LrcParser {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val tag = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * Parses LRC and enhanced LRC: several time stamps per line, `[offset:±ms]` (positive = sooner),
     * metadata tags ignored and per-word `<mm:ss.xx>` stamps stripped. Lines come back sorted by time.
     */
    fun parse(text: String): List<LyricLine> {
        var offset = 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val stamps = generateSequence(stamp.matchAt(line, 0)) { m -> stamp.matchAt(line, m.range.last + 1) }.toList()
            if (stamps.isEmpty()) {
                tag.matchEntire(line.trim())?.let { m ->
                    if (m.groupValues[1].equals("offset", ignoreCase = true)) offset = m.groupValues[2].trim().toLongOrNull() ?: offset
                }
                continue
            }
            val words = line.substring(stamps.last().range.last + 1).replace(wordStamp, "").trim()
            for (m in stamps) {
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs, words)
            }
        }
        return out.map { it.copy(startMs = ((it.startMs ?: 0) - offset).coerceAtLeast(0)) }.sortedBy { it.startMs }
    }
}

/** Picks the best entry Navidrome returned: the main track, synced if there is one. */
internal fun List<StructuredLyricsDto>.toLyrics(): Lyrics? {
    val main = filter { (it.kind == null || it.kind == "main") && it.line.any { l -> l.value.isNotBlank() } }
    val pick = main.firstOrNull { it.synced } ?: main.firstOrNull() ?: return null
    val lines = if (pick.synced) {
        pick.line.map { LyricLine(((it.start ?: 0) - pick.offset).coerceAtLeast(0), it.value.trimEnd()) }.sortedBy { it.startMs }
    } else {
        pick.line.flatMap { it.value.lines() }.map { LyricLine(null, it.trimEnd()) }
    }
    return Lyrics(LyricsSource.Server, pick.synced, lines)
}

internal fun plainLyrics(text: String, source: LyricsSource) =
    Lyrics(source, synced = false, lines = text.trim().lines().map { LyricLine(null, it.trimEnd()) })
