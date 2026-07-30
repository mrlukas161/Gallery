package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.QrDatabase

// Rozšírenie ŠTANDARDNÉHO hľadania galérie: okrem názvu súboru vráti aj cesty fotiek, ktoré
// dopytu zodpovedajú cez MENO osoby, OCR text alebo QR kód.
//
// DÔLEŽITÉ PRAVIDLO (oprava po spätnej väzbe „napísal som peter a dostal som mačky"):
// CLIP sémantické hľadanie je len ZÁLOHA. Ak sa nájde čo i len jedna PRESNÁ zhoda (meno osoby,
// text na fotke, QR), vrátia sa LEN presné zhody — CLIP sa vôbec nepoužije, aby výsledky
// nezaplavili náhodné „podobné" fotky. CLIP sa spustí iba vtedy, keď presná zhoda neexistuje,
// a to s prísnejším prahom.
object SmartSearch {

    // posledný hľadaný výraz — TextSelectActivity ním predznačí nájdené slová priamo na fotke
    @Volatile
    var lastQuery: String = ""


    private const val CLIP_MIN_COS = 0.23f
    private const val CLIP_TOP_N = 40

    fun extraPaths(context: Context, query: String): Set<String> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptySet()
        val norm = tokens.map { TextNormalizer.normalize(it, true) }.filter { it.isNotEmpty() }
        if (norm.isEmpty()) return emptySet()

        val exact = HashSet<String>()
        var namedPersonQuery = false

        // 1) Osoby — najsilnejší signál: ak dopyt sedí na meno osoby, hľadá sa človek, nie scéna
        try {
            val people = PeopleDatabase.getInstance(context).PeopleDao()
            val persons = people.getPersons()
            val matchedIds = persons.filter { p ->
                val n = TextNormalizer.normalize(p.name ?: "", true)
                n.isNotEmpty() && norm.any { t -> n.contains(t) || t.contains(n) }
            }.mapNotNull { it.id }.toHashSet()
            if (matchedIds.isNotEmpty()) {
                namedPersonQuery = true
                val pidByFace = people.getAssignments().associate { it.faceId to it.personId }
                val faces = FacesDatabase.getInstance(context).FaceDao()
                for (f in faces.getAllFaces()) {
                    val id = f.id ?: continue
                    val pid = pidByFace[id] ?: continue
                    if (matchedIds.contains(pid)) exact.add(f.mediaFullPath)
                }
            }
        } catch (ignored: Throwable) {
        }

        // 2) OCR: fotka pasuje, ak jej text obsahuje VŠETKY tokeny (prienik)
        try {
            val ocr = OcrDatabase.getInstance(context).OcrDao()
            val sets = norm.map { ocr.search(it).toHashSet() }
            if (sets.isNotEmpty()) {
                exact.addAll(sets.reduce { a, b -> a.intersect(b).toHashSet() })
            }
        } catch (ignored: Throwable) {
        }

        // 3) QR/čiarový kód: obsah kódu obsahuje niektorý token
        try {
            val qr = QrDatabase.getInstance(context).QrDao()
            norm.forEach { exact.addAll(qr.search(it)) }
        } catch (ignored: Throwable) {
        }

        // presné zhody vyhrávajú — žiadne domiešavanie „podobných" fotiek
        if (exact.isNotEmpty()) return exact

        // dopyt na konkrétne meno bez výsledku: radšej nič než náhodné fotky
        if (namedPersonQuery) return emptySet()

        // 4) až teraz CLIP (predmety/scény), prísnejšie
        return try {
            org.fossify.gallery.clip.ClipSearch.search(context, query, CLIP_TOP_N, CLIP_MIN_COS).toSet()
        } catch (e: Throwable) {
            emptySet()
        }
    }
}
