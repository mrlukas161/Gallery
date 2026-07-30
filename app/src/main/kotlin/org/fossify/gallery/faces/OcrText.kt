package org.fossify.gallery.faces

// Filter „je toto naozaj text?" — Tesseract na fotke bez textu (mačka, krajina) vyrobí z artefaktov
// zhluky znakov typu `j ' ,, ~~ ee`. Bez tejto kontroly potom KAŽDÁ fotka „má text", čo zaplaví
// album Dokumenty aj vyhľadávanie. Filter je čisto textový, takže sa dá použiť aj spätne na už
// uložené záznamy (bez opätovného rozpoznávania).
object OcrText {

    private val WORD = Regex("""[\p{L}]{2,}""")

    // slovo vyzerá ako slovo: nie je to zhluk súhlások/opakovaní typu „eeee", „xhq"
    private fun plausibleWord(w: String): Boolean {
        if (w.length < 3) return false
        val lower = w.lowercase()
        val vowels = lower.count { it in "aáäeéiíyýoóôuúrl" }
        if (vowels == 0) return false
        // „aaaa", „mmm" — jeden znak dokola
        if (lower.toSet().size <= 1) return false
        return true
    }

    // Prísne rozhodnutie, či text uložiť. Prah je zámerne vyšší — radšej text neuložiť, než mať
    // v Dokumentoch 10 000 fotiek.
    fun isMeaningful(text: String): Boolean {
        val t = text.trim()
        if (t.length < 12) return false
        val words = WORD.findAll(t).map { it.value }.toList()
        if (words.size < 3) return false
        val good = words.count { plausibleWord(it) }
        if (good < 3) return false
        // aspoň polovica nájdených slov musí vyzerať ako slová
        if (good.toFloat() / words.size < 0.5f) return false
        // pomer písmen a číslic voči všetkým neprázdnym znakom (šum má veľa interpunkcie)
        val visible = t.count { !it.isWhitespace() }
        if (visible == 0) return false
        val alnum = t.count { it.isLetterOrDigit() }
        if (alnum.toFloat() / visible < 0.62f) return false
        // dosť písmen celkovo
        if (t.count { it.isLetter() } < 12) return false
        return true
    }

    // Vyhodí zjavne šumové riadky (samé symboly, jednotlivé znaky), zvyšok nechá.
    fun clean(text: String): String {
        val kept = text.lines().filter { line ->
            val l = line.trim()
            if (l.length < 3) return@filter false
            val visible = l.count { !it.isWhitespace() }
            if (visible == 0) return@filter false
            l.count { it.isLetterOrDigit() }.toFloat() / visible >= 0.5f
        }
        return kept.joinToString("\n").trim()
    }

    // Minimálna dôvera Tesseractu (0–100). Pod ňou text zahadzujeme.
    const val MIN_CONFIDENCE = 62
}
