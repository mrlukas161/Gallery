package org.fossify.gallery.faces

import org.fossify.gallery.family.FaceMatch
import org.fossify.gallery.family.RelBox

// A5: čistá logika znovunájdenia presunutých fotiek (FaceRelinker) bez Androidu, testovaná v RelinkPlannerTest.
//
// Fotka, ktorá zmizla zo svojej cesty (presun, premenovanie, skrytie, SD karta pod inou cestou,
// obnova z Koša pod iným menom), sa nájde podľa obsahového kľúča (family.ContentKey) a veľkosti.
// Preklopí sa len pri JEDNOZNAČNEJ zhode 1:1 — jedna chýbajúca cesta a jediný kandidát s týmto
// kľúčom. Pri viacerých kandidátoch (kópie) alebo viacerých chýbajúcich cestách s rovnakým obsahom
// sa nerobí nič: radšej nechať tváre odpojené, než menovky prilepiť na nesprávnu kópiu.
object RelinkPlanner {

    // chýbajúca cesta s tvárami a jej kľúč/veľkosť uložené ešte pred presunom (content_keys)
    data class Missing(val path: String, val size: Long, val contentKey: String)

    // existujúca fotka z MediaStore (alebo skrytá dvojička) s vypočítaným kľúčom
    data class Candidate(val path: String, val size: Long, val contentKey: String, val mediaStoreId: Long)

    data class Match(val missing: Missing, val candidate: Candidate)

    // tvár pre rozhodnutie o kolízii: id a poradie na fotke (unikátny index (media_full_path, face_index))
    data class FaceSlot(val id: Long, val faceIndex: Int)

    sealed interface Action {
        // UPDATE faces na novú cestu; predtým zmazať tieto nepomenované duplikáty z indexera na novej ceste
        data class Move(val deleteIds: List<Long>) : Action

        // nová cesta má tváre, na ktoré niečo odkazuje → bez UPDATE, len preniesť priradenia cez IoU
        data object Transfer : Action
    }

    // Jednoznačné páry chýbajúca cesta → kandidát (1:1 podľa kľúča aj veľkosti).
    fun plan(missing: List<Missing>, candidates: List<Candidate>): List<Match> {
        val missingPaths = missing.mapTo(HashSet()) { it.path }
        val missingByKey = missing.groupBy { it.contentKey }
        val candidatesByKey = candidates
            .filter { it.path !in missingPaths }
            .distinctBy { it.path }
            .groupBy { it.contentKey }
        val out = ArrayList<Match>()
        for ((key, olds) in missingByKey) {
            val old = olds.first()
            val matches = candidatesByKey[key].orEmpty().filter { it.size == old.size }
            // viac chýbajúcich ciest s rovnakým obsahom alebo žiadny/viac kandidátov (kópie) = nejednoznačné
            if (olds.size == 1 && matches.size == 1) out.add(Match(old, matches.first()))
        }
        return out.sortedBy { it.missing.path }
    }

    // Čo spraviť, keď nová cesta už má tváre. referenced = id tvárí, na ktoré odkazuje people.db,
    // extras.db alebo family.db (proposals); null = odkazy sa nedali prečítať → nič nemazať.
    fun decide(oldFaces: List<FaceSlot>, newFaces: List<FaceSlot>, referenced: Set<Long>?): Action {
        if (newFaces.isEmpty()) return Action.Move(emptyList())
        if (referenced == null) return Action.Transfer
        val deletable = newFaces.filter { it.id !in referenced }
        // zmazať sa smú len nepomenované duplikáty; keď ostane čo i len jedna tvár s odkazom, UPDATE
        // by narazil na unikátny index (media_full_path, face_index) alebo by na fotke vznikli dvojice
        if (deletable.size != newFaces.size) return Action.Transfer
        val remaining = newFaces - deletable.toSet()
        if (collides(oldFaces, remaining)) return Action.Transfer
        return Action.Move(deletable.map { it.id })
    }

    // Narazil by UPDATE starých tvárí na novú cestu na unikátny index (media_full_path, face_index)?
    fun collides(oldFaces: List<FaceSlot>, remainingNewFaces: List<FaceSlot>): Boolean {
        val taken = remainingNewFaces.mapTo(HashSet()) { it.faceIndex }
        return oldFaces.any { it.faceIndex in taken }
    }

    // Prenos priradení (Transfer): ku každej starej tvári najviac jedna nová cez FaceMatch.best (IoU,
    // jednoznačne). Stará tvár môže mať dve hypotézy rámčeka (upright / surový priestor pred 0.47).
    // Na jednu novú tvár sa nesmú preniesť dve staré — taká nová tvár sa vynechá celá.
    fun <T> transferPairs(
        oldFaces: List<Pair<T, List<RelBox>>>,
        newFaces: List<Pair<T, List<RelBox>>>,
    ): Map<T, T> {
        val raw = LinkedHashMap<T, T>()
        for ((oldId, boxes) in oldFaces) {
            val best = FaceMatch.best(boxes, newFaces) ?: continue
            raw[oldId] = best
        }
        val counts = raw.values.groupingBy { it }.eachCount()
        return raw.filterValues { counts[it] == 1 }
    }
}
