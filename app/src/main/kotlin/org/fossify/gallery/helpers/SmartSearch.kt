package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.QrDatabase

// Rozšírenie ŠTANDARDNÉHO hľadania galérie: okrem názvu súboru vráti aj cesty fotiek, ktoré
// dopytu zodpovedajú cez OCR text, QR kód alebo MENO priradenej osoby. Volá sa z SearchActivity
// a MediaActivity (hľadanie v albume) — žiadna vlastná obrazovka.
object SmartSearch {
    fun extraPaths(context: Context, query: String): Set<String> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptySet()
        val norm = tokens.map { TextNormalizer.normalize(it, true) }.filter { it.isNotEmpty() }
        if (norm.isEmpty()) return emptySet()
        val result = HashSet<String>()

        // OCR: fotka pasuje, ak jej text obsahuje VŠETKY tokeny (prienik)
        try {
            val ocr = OcrDatabase.getInstance(context).OcrDao()
            val sets = norm.map { ocr.search(it).toHashSet() }
            if (sets.isNotEmpty()) {
                result.addAll(sets.reduce { a, b -> a.intersect(b).toHashSet() })
            }
        } catch (ignored: Throwable) {
        }

        // QR/čiarový kód: obsah kódu obsahuje niektorý token
        try {
            val qr = QrDatabase.getInstance(context).QrDao()
            norm.forEach { result.addAll(qr.search(it)) }
        } catch (ignored: Throwable) {
        }

        // Osoby: fotka obsahuje osobu, ktorej meno obsahuje niektorý token
        try {
            val people = PeopleDatabase.getInstance(context).PeopleDao()
            val persons = people.getPersons()
            val matchedIds = persons.filter { p ->
                val n = TextNormalizer.normalize(p.name ?: "", true)
                n.isNotEmpty() && norm.any { n.contains(it) }
            }.mapNotNull { it.id }.toHashSet()
            if (matchedIds.isNotEmpty()) {
                val pidByFace = people.getAssignments().associate { it.faceId to it.personId }
                val faces = FacesDatabase.getInstance(context).FaceDao()
                for (f in faces.getAllFaces()) {
                    val id = f.id ?: continue
                    val pid = pidByFace[id] ?: continue
                    if (matchedIds.contains(pid)) result.add(f.mediaFullPath)
                }
            }
        } catch (ignored: Throwable) {
        }

        return result
    }
}
