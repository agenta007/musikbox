package app.musikbox.data

import java.text.Normalizer

/**
 * Search inside a playlist: every word typed must appear in the title, artist or album, ignoring
 * case and accents, so "bjork joga" finds "Jóga" by Björk.
 */
fun matchesSearch(query: String, vararg fields: String?): Boolean {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val text = fold(fields.filterNotNull().joinToString(" "))
    return words.all { it in text }
}

private val MARKS = Regex("\\p{M}+")

private fun fold(text: String): String =
    MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
