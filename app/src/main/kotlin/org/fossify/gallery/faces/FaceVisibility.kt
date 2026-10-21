package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.extensions.getParentPath
import org.fossify.gallery.extensions.config
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
    private val lockedByFolder = HashMap<String, Boolean>()

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

    // Zamknutý priečinok (PIN/heslo) — tá istá podmienka ako v IndexScope. Pri chybe čítania nastavení
    // sa berie ako zamknutý (súkromie má prednosť).
    @Synchronized
    fun isLocked(path: String): Boolean = lockedByFolder.getOrPut(path.getParentPath()) {
        try {
            appContext.config.isFolderProtected(path.getParentPath())
        } catch (ignored: RuntimeException) {
            true
        }
    }

    // Osoba ukáže len viditeľné tváre (aj počet a titulná tvár sa rátajú z nich). Osoba, ktorej tváre
    // boli všetky skryté, ostane sivo so značkou photosMissing („fotky sa nenašli") — okrem osoby so
    // zamknutými fotkami, tá sa kvôli súkromiu neukáže (PersonGrouper.visiblePerson). V people.db ostáva.
    // Osoba, ktorá tváre nemala ani predtým (napr. len so vzormi z Picasy), sa správa ako doteraz.
    fun filterPersons(persons: List<Person>): List<Person> = persons.mapNotNull { person ->
        PersonGrouper.visiblePerson(person, ::isVisible, ::isLocked)
    }

    // obrazovka sama zmazala/presunula/premenovala tieto fotky — nanovo ich zistiť pri ďalšom načítaní
    @Synchronized
    fun forget(paths: Collection<String>) {
        paths.forEach { byPath.remove(it) }
    }
}
