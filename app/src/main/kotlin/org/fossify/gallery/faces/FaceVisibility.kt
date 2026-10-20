package org.fossify.gallery.faces

import android.content.Context
import java.io.File

// Ktoré tváre sa smú UKÁZAŤ v UI osôb (Ľudia, Osoba, Označiť osoby / Návrhy). Skrytá je tvár, ktorej
// fotka už na uloženej ceste neexistuje (presun/premenovanie/zmazanie — faces.db ostáva na starej
// ceste), alebo ktorej priečinok je mimo rozsahu spracovania (zamknutý PIN-om/heslom či vylúčený,
// rovnaké pravidlá ako IndexScope). Ide LEN o zobrazenie: v databázach sa nič nemaže ani nemení,
// priradenia k osobám ostávajú. Trvalé riešenie presunov príde s identitou fotky podľa obsahu (contentKey).
//
// Volať LEN z vlákna na pozadí — prvé zistenie pre cestu siaha na disk (File.exists).
// Výsledok sa pamätá per cesta na dobu života inštancie (jedna inštancia = jedna otvorená obrazovka).
class FaceVisibility(context: Context) {
    private val appContext = context.applicationContext
    private val byPath = HashMap<String, Boolean>()

    // IndexScope nie je thread-safe — používa sa len pod zámkom tejto inštancie
    private var scope: IndexScope? = null

    @Synchronized
    fun isVisible(path: String): Boolean = byPath.getOrPut(path) {
        val currentScope = scope ?: IndexScope(appContext).also { scope = it }
        try {
            currentScope.allows(path) && File(path).exists()
        } catch (ignored: SecurityException) {
            // bez prístupu k súboru ho radšej neukázať (súkromie má prednosť)
            false
        }
    }

    fun filterFaces(faces: List<FaceEntity>): List<FaceEntity> = faces.filter { isVisible(it.mediaFullPath) }

    // Osoba ukáže len viditeľné tváre (aj počet a titulná tvár sa rátajú z nich). Osoba, ktorej tváre
    // boli všetky skryté, z výsledku vypadne — v people.db ostáva. Osoba, ktorá tváre nemala ani
    // predtým (napr. len so vzormi z Picasy), sa správa ako doteraz.
    fun filterPersons(persons: List<Person>): List<Person> = persons.mapNotNull { person ->
        val visible = filterFaces(person.faces)
        when {
            visible.size == person.faces.size -> person
            visible.isEmpty() -> null
            else -> {
                val visibleIds = visible.mapNotNullTo(HashSet()) { it.id }
                person.copy(faces = visible, manualFaceIds = person.manualFaceIds.intersect(visibleIds))
            }
        }
    }

    // obrazovka sama zmazala/presunula/premenovala tieto fotky — nanovo ich zistiť pri ďalšom načítaní
    @Synchronized
    fun forget(paths: Collection<String>) {
        paths.forEach { byPath.remove(it) }
    }
}
