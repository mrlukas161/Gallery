package org.fossify.gallery.helpers

// Slová dopytu pre SmartSearch: ktoré sa smú porovnať s menom osoby, textom na fotke (OCR) a QR kódom.
// Čistá logika bez Androidu (SearchTermsTest).
//
// Revízia 11/2026: meno osoby sa porovnávalo ako podreťazec („les“ našlo Aleša, „a“ takmer každého),
// takže dopyt „pes a mačka“ vrátil fotky osôb a CLIP sa vôbec nespustil. Teraz:
//  - spojky a predložky (a, na, v, s, …) sa nikde neporovnávajú,
//  - meno osoby sedí len na CELÉ slovo mena alebo jeho slovenský tvar (Peter → Petra, Petrovi, Petrom;
//    Mária → Márie, Máriou; Lukáš → Lukášove), nie na kúsok slova,
//  - názov mesiaca („máj“, „júna“) sa s menom porovná len doslova (osoba Maja áno, „máj“ na Maju nie),
//  - QR (hľadá sa podreťazec) dostane len slová s aspoň dvoma znakmi.
object SearchTerms {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    private const val MIN_STEM = 2
    private const val MIN_INFLECTED = 3
    private const val MIN_QR_TOKEN = 2

    // normalizované (malé písmená, bez diakritiky)
    private val STOP_WORDS = setOf(
        "a", "aj", "ale", "alebo", "ani", "ci", "i", "k", "ku", "na", "nad", "o", "od", "po", "pod", "pre",
        "pred", "pri", "s", "so", "u", "v", "vo", "z", "zo", "za", "ze", "do", "cez", "bez", "medzi", "ako",
        "the", "and", "or", "of", "in", "on", "with", "at", "to",
    )

    private val MONTHS = setOf(
        "januar", "januara", "januari", "februar", "februara", "februari", "marec", "marca", "marci",
        "april", "aprila", "aprili", "maj", "maja", "maji", "jun", "juna", "juni", "jul", "jula", "juli",
        "august", "augusta", "auguste", "septembri", "september", "septembra", "oktober", "oktobra", "oktobri",
        "november", "novembra", "novembri", "december", "decembra", "decembri",
    )

    // koncovky slovenských tvarov mien (po odtrhnutí koncovej samohlásky / pohyblivého e); samotný kmeň
    // („Han“ z Hany, „Petr“ z Petra) tvarom nie je
    private val ENDINGS = setOf(
        "a", "e", "i", "o", "u", "y", "ou", "om", "ov", "ovi", "ove", "ova", "ovo", "ovu", "ovej", "ovych",
        "ovym", "ej", "ia", "ie", "ii", "iu", "iou", "ko", "ka", "ke", "ku",
    )

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val MOBILE_E = setOf("ek", "el", "er", "ec", "en")

    // slová dopytu bez prázdnych; vstup už normalizovaný (TextNormalizer)
    fun split(normalized: String): List<String> = normalized.split(SPLIT).filter { it.isNotEmpty() }

    fun isStopWord(token: String) = token in STOP_WORDS

    fun isMonth(token: String) = token in MONTHS

    // slová, ktoré nesú význam (OCR: fotka musí obsahovať všetky)
    fun meaningful(tokens: List<String>): List<String> = tokens.filter { !isStopWord(it) }

    // slová pre QR (podreťazec obsahu kódu): bez spojok, mesiacov a jednoznakových slov
    fun forQr(tokens: List<String>): List<String> =
        meaningful(tokens).filter { it.length >= MIN_QR_TOKEN && !isMonth(it) }

    // Sedí slovo dopytu na meno osoby? name aj token normalizované. Meno môže mať viac slov.
    fun nameMatches(name: String, token: String): Boolean {
        if (token.isEmpty() || isStopWord(token)) return false
        val words = split(name)
        if (words.any { it == token }) return true
        if (isMonth(token) || token.length < MIN_INFLECTED) return false
        return words.any { inflectionOf(it, token) }
    }

    // token je slovenský tvar slova mena: kmeň (slovo, bez koncovej samohlásky, bez pohyblivého e)
    // + známa koncovka. Kmeň má aspoň MIN_STEM znakov a tvar aspoň MIN_INFLECTED (krátke „id“ nesedí na Idu).
    private fun inflectionOf(word: String, token: String): Boolean =
        stems(word).any { stem ->
            stem.length >= MIN_STEM && token.startsWith(stem) && token.substring(stem.length) in ENDINGS
        }

    private fun stems(word: String): List<String> {
        val out = arrayListOf(word)
        if (word.length > MIN_STEM && word.last() in VOWELS) out.add(word.dropLast(1))
        // Peter → Petr(a), Marek → Mark(a), Pavel → Pavl(a)
        if (word.length > MIN_STEM + 1 && word.takeLast(2) in MOBILE_E) {
            out.add(word.dropLast(2) + word.last())
        }
        return out
    }
}
