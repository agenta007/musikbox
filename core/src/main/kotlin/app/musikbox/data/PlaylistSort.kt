package app.musikbox.data

import java.text.Normalizer
import java.util.Locale

/**
 * How the playlist list is ordered. [label] is the normal direction, [reversedLabel] the
 * other way round. Playlists without the value (never played, no date) always go last.
 */
enum class PlaylistSort(val label: String, val reversedLabel: String) {
    Name("Name", "Name, Z to A"),
    Added("Date added", "Date added, oldest first"),
    Updated("Recently updated", "Least recently updated"),
    Played("Recently played", "Least recently played"),
    Songs("Most songs", "Fewest songs"),
    Length("Longest", "Shortest"),
    Owner("Owner", "Owner, Z to A");

    fun label(reversed: Boolean) = if (reversed) reversedLabel else label

    companion object {
        /** A stored name back to a sort; unknown or old names fall back to [Name]. */
        fun parse(stored: String?): PlaylistSort = entries.firstOrNull { it.name == stored } ?: Name
    }
}

/** What a playlist is sorted by, so both apps can sort their own playlist types. */
data class PlaylistSortFields(
    val name: String,
    val owner: String? = null,
    /** Server timestamps (ISO 8601, so they sort as text). */
    val created: String? = null,
    val changed: String? = null,
    val lastPlayedAt: Long? = null,
    val songCount: Int = 0,
    val durationSec: Long = 0,
)

/**
 * Sorts playlists by [sort], ties by name. Names compare as people read them, case and accents
 * aside, with Latin before Cyrillic and Cyrillic in alphabet order, whatever the device's
 * language (the desktop JVM's collator puts "В" before "б" outside a Bulgarian locale).
 */
fun <T> List<T>.sortedForPlaylists(sort: PlaylistSort, reversed: Boolean, fields: (T) -> PlaylistSortFields): List<T> {
    class Row(val item: T, val f: PlaylistSortFields) {
        val name = nameSortKey(f.name)
        // The value to sort by, oriented so that ascending order is the normal direction; null = missing.
        val key: Comparable<*>? = when (sort) {
            PlaylistSort.Name -> null
            PlaylistSort.Added -> f.created?.takeIf { it.isNotBlank() }?.let(::Descending)
            PlaylistSort.Updated -> f.changed?.takeIf { it.isNotBlank() }?.let(::Descending)
            PlaylistSort.Played -> f.lastPlayedAt?.let(::Descending)
            PlaylistSort.Songs -> Descending(f.songCount)
            PlaylistSort.Length -> Descending(f.durationSec)
            PlaylistSort.Owner -> f.owner?.takeIf { it.isNotBlank() }?.let(::nameSortKey)
        }
    }
    val byName = compareBy<Row>({ it.name }, { it.f.name })
    val order: Comparator<Row> = if (sort == PlaylistSort.Name) {
        if (reversed) byName.reversed() else byName
    } else {
        Comparator<Row> { a, b ->
            @Suppress("UNCHECKED_CAST")
            val ka = a.key as Comparable<Any>?
            @Suppress("UNCHECKED_CAST")
            val kb = b.key as Comparable<Any>?
            when {
                ka == null && kb == null -> 0
                ka == null -> 1 // missing values last, in both directions
                kb == null -> -1
                reversed -> kb.compareTo(ka)
                else -> ka.compareTo(kb)
            }
        }.then(byName)
    }
    return map { Row(it, fields(it)) }.sortedWith(order).map { it.item }
}

/**
 * Lower case without accents ("Émotions" -> "emotions"), for sorting. Cyrillic letters stay
 * whole, so "й" and "ё" keep their own places; the Cyrillic block is in alphabet order.
 */
internal fun nameSortKey(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)) {
        if (ch in '\u0400'..'\u04FF') {
            out.append(ch)
        } else {
            Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                .filterTo(out) { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        }
    }
    return out.toString()
}

/** Newest / biggest first when sorted ascending. */
private class Descending<V : Comparable<V>>(val value: V) : Comparable<Descending<V>> {
    override fun compareTo(other: Descending<V>) = other.value.compareTo(value)
}
