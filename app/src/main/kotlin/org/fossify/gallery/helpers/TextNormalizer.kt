package org.fossify.gallery.helpers

import java.text.Normalizer

// Uvoľnené vyhľadávanie: malé písmená + (voliteľne) bez diakritiky. simon = Šimon = šimon = Simon.
object TextNormalizer {
    private val MARKS = Regex("\\p{Mn}+")

    fun normalize(s: String, stripDiacritics: Boolean = true): String {
        val lower = s.lowercase().trim()
        if (!stripDiacritics) return lower
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }
}
