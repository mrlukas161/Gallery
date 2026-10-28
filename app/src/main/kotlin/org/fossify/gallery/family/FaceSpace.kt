package org.fossify.gallery.family

import org.fossify.gallery.faces.FaceBoxSpace
import kotlin.math.sqrt

// V akom priestore je uložený rámček tváre (faces.db) na fotke s EXIF otočením (D2, D3).
// UPRIGHT = bitmapa z UprightDecoder.decode(path, 1024) (FaceIndexer od 0.47), RAW = neotočená bitmapa
// (staré záznamy), BROKEN = overenie odtlačkom nesedí ani v jednom priestore (pokazený odtlačok alebo iná
// verzia modelu) — taká tvár sa neposiela.
enum class BoxSpace(val code: String) {
    UPRIGHT("upright"),
    RAW("raw"),
    BROKEN("broken");

    companion object {
        fun of(code: String?): BoxSpace? = entries.firstOrNull { it.code == code }
    }
}

// Rozhodovanie o priestore rámčeka. Čistá logika bez Androidu (FaceSpaceTest, BundleRulesTest).
//
// Poradie dôkazov pre fotku s otočením/zrkadlením:
//  0) E1: uložený box_space (1/2) — priestor je istý, nič sa nehádá;
//  1) uložené overenie odtlačkom (face_space), ak je v súlade s rozmermi;
//  2) geometria: rámček, ktorý sa zmestí len do jedného z priestorov, patrí doň;
//  3) overenie odtlačkom teraz (len pri odosielaní, lenivo — prepočet výrezu pre obe hypotézy);
//  4) posledná záloha: detected_at ≥ 31.8.2026 (BundleRules.UPRIGHT_SINCE_MS) = upright;
//  5) inak nejasné (null) — taká tvár sa vynechá sama, nie celá fotka.
// Neotočená fotka má jediný priestor; rámček, ktorý presahuje obrázok o viac než ~2 %, je nejasný.
object FaceSpaceRules {
    // overenie: kosínus prepočítaného a uloženého odtlačku, od ktorého ide o ten istý výrez
    const val MIN_COSINE = 0.95f

    // keď sedia obe hypotézy (napr. symetrická tvár v strede štvorcovej fotky), musí jedna jasne vyhrať
    const val TIE_MARGIN = 0.02f

    // rámček môže presahovať obrázok najviac o ~2 % strany (+1 px zaokrúhlenia RelBox.decodedUprightSize)
    const val MAX_OVERFLOW = 0.02f
    private const val EDGE_PX = 1

    private const val FULL_TURN = 360

    fun isRotated(transform: RelBox.Transform): Boolean =
        Math.floorMod(transform.rotationDegrees, FULL_TURN) != 0 || transform.mirrored

    // zmestí sa rámček do obrázka width × height (s toleranciou MAX_OVERFLOW)?
    fun fits(box: StoredBox, width: Int, height: Int): Boolean {
        val dx = width * MAX_OVERFLOW + EDGE_PX
        val dy = height * MAX_OVERFLOW + EDGE_PX
        return box.right > box.left && box.bottom > box.top &&
            box.left >= -dx && box.top >= -dy && box.right <= width + dx && box.bottom <= height + dy
    }

    // Výsledok overenia z kosínusov oboch hypotéz (null = hypotéza sa nedala spočítať).
    // null = overiť sa nedá (chýba odtlačok, model sa nenačítal); BROKEN = počítalo sa, ale nič nesedí.
    fun fromCosines(upright: Float?, raw: Float?): BoxSpace? {
        if (upright == null && raw == null) return null
        val u = upright ?: -1f
        val r = raw ?: -1f
        return when {
            u < MIN_COSINE && r < MIN_COSINE -> BoxSpace.BROKEN
            u >= MIN_COSINE && r >= MIN_COSINE -> when {
                u - r >= TIE_MARGIN -> BoxSpace.UPRIGHT
                r - u >= TIE_MARGIN -> BoxSpace.RAW
                else -> null // obe rovnako dobré: overenie nerozhodlo
            }

            u >= MIN_COSINE -> BoxSpace.UPRIGHT
            else -> BoxSpace.RAW
        }
    }

    // Priestor rámčeka (UPRIGHT/RAW), BROKEN, alebo null = nejasné.
    // cached = uložené overenie, verify = overenie odtlačkom teraz (null = v tomto kontexte sa neoveruje).
    fun resolve(
        box: StoredBox,
        rawWidth: Int,
        rawHeight: Int,
        transform: RelBox.Transform,
        cached: BoxSpace? = null,
        verify: (() -> BoxSpace?)? = null,
    ): BoxSpace? {
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val max = FaceMatch.DECODE_MAX_SIZE
        val upright = RelBox.decodedUprightSize(rawWidth, rawHeight, max, transform.rotationDegrees)
        val fitsUpright = fits(box, upright[0], upright[1])
        // E1: priestor je uložený (box_space 1/2) alebo fotka nie je otočená — nič sa nehádá
        if (singleSpace(box, transform)) return if (fitsUpright) BoxSpace.UPRIGHT else null
        val raw = RelBox.decodedUprightSize(rawWidth, rawHeight, max, 0)
        val fitsRaw = fits(box, raw[0], raw[1])
        if (consistent(cached, fitsUpright, fitsRaw)) return cached
        return when {
            fitsUpright && !fitsRaw -> BoxSpace.UPRIGHT
            fitsRaw && !fitsUpright -> BoxSpace.RAW
            !fitsUpright -> null // nesedí do žiadneho priestoru
            else -> verify?.invoke() ?: if (box.detectedAt >= BundleRules.UPRIGHT_SINCE_MS) BoxSpace.UPRIGHT else null
        }
    }

    private fun singleSpace(box: StoredBox, transform: RelBox.Transform): Boolean =
        !isRotated(transform) || FaceBoxSpace.isKnown(box.boxSpace)

    // uložené overenie platí, len keď sa rámček do daného priestoru zmestí
    private fun consistent(cached: BoxSpace?, fitsUpright: Boolean, fitsRaw: Boolean) = when (cached) {
        BoxSpace.UPRIGHT -> fitsUpright
        BoxSpace.RAW -> fitsRaw
        BoxSpace.BROKEN -> true
        null -> false
    }

    // Relatívny upright rámček pre určený priestor (UPRIGHT/RAW), inak null.
    fun relBox(box: StoredBox, rawWidth: Int, rawHeight: Int, transform: RelBox.Transform, space: BoxSpace?): RelBox? {
        val max = FaceMatch.DECODE_MAX_SIZE
        return when (space) {
            BoxSpace.UPRIGHT -> {
                val s = RelBox.decodedUprightSize(rawWidth, rawHeight, max, transform.rotationDegrees)
                // E1: upright rámček, ktorý nie je v2 (box_space 1, alebo 0 — v2 rámčeky majú vždy 2), je na
                // fotke 5/7 o 180° otočený oproti zobrazeniu (pôvodná chyba UprightDecoder)
                val space = if (box.boxSpace == FaceBoxSpace.UPRIGHT_V2) box.boxSpace else FaceBoxSpace.UPRIGHT_V1
                val b = FaceBoxSpace.toV2(
                    intArrayOf(box.left, box.top, box.right, box.bottom), space, FaceBoxSpace.isTransposed(transform),
                    s[0], s[1],
                )
                RelBox.fromAbsolute(b[0], b[1], b[2], b[b.size - 1], s[0], s[1])
            }

            BoxSpace.RAW -> {
                val s = RelBox.decodedUprightSize(rawWidth, rawHeight, max, 0)
                RelBox.fromAbsolute(box.left, box.top, box.right, box.bottom, s[0], s[1])
                    .transformed(transform.rotationDegrees, transform.mirrored)
            }

            else -> null
        }
    }

    // Hypotézy rámčeka na párovanie u prijímateľa: jedna, keď je priestor istý, inak obe (FaceMatch.best
    // potom žiada jednoznačnú zhodu). BROKEN = žiadna (tvár sa nepáruje).
    fun hypotheses(
        box: StoredBox,
        rawWidth: Int,
        rawHeight: Int,
        transform: RelBox.Transform,
        cached: BoxSpace?,
    ): List<RelBox> {
        if (rawWidth <= 0 || rawHeight <= 0 || cached == BoxSpace.BROKEN) return emptyList()
        val space = resolve(box, rawWidth, rawHeight, transform, cached)
        if (space == BoxSpace.BROKEN) return emptyList()
        relBox(box, rawWidth, rawHeight, transform, space)?.let { return listOf(it) }
        return FaceMatch.relBoxes(
            box.left, box.top, box.right, box.bottom, rawWidth, rawHeight, transform, box.boxSpace,
        )
    }

    // Odtlačok rámčeka a uloženého vektora: zmena ktoréhokoľvek zneplatní uložené overenie.
    fun stamp(box: StoredBox, embedding: ByteArray?): Long {
        var h = HASH_SEED
        for (v in intArrayOf(box.left, box.top, box.right, box.bottom)) h = h * HASH_PRIME + v
        return h * HASH_PRIME + (embedding?.contentHashCode() ?: 0)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return -1f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val norm = sqrt(na) * sqrt(nb)
        return if (norm <= 0.0) -1f else (dot / norm).toFloat()
    }

    private const val HASH_SEED = 17L
    private const val HASH_PRIME = 31L
}
