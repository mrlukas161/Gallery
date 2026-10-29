package org.fossify.gallery.helpers

import android.content.Context
import androidx.annotation.WorkerThread
import org.fossify.gallery.faces.AssignmentSource
import org.fossify.gallery.faces.CannotLinkEntity
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.family.FamilyRecorder

// Ručné rozhodnutia o tvárach, ktoré sa učia aj z toho, čo tvár NIE JE (C8, C11):
//  - presun tváre z osoby A na B zapíše priradenie k B a zároveň cannot-link (tvár, A). Osoba A sa zistí
//    z priradení v people.db a z jedného zdroja pravdy (PeopleIndex: automaticky alebo neisto zaradená);
//  - „Je to Ján, alebo Peter?“ (C11): odpoveď zapíše priradenie aj cannot-link na druhú osobu,
//    „niekto iný“ cannot-link na obe.
// Zápis do people.db je jedna transakcia, potom ide zmena cez FamilyRecorder (ten zároveň zneplatní
// PeopleIndex). Volať LEN z pozadia.
object FaceDecisions {
    // Priradí tváre osobe [targetId]; tváre, ktoré doteraz patrili inej osobe, dostanú cannot-link na ňu.
    // [knownFrom] = osoba, z ktorej karty sa presúva (všetky tváre výberu jej patria, aj automaticky).
    // E2: [source] = pôvod priradenia (AssignmentSource), [confidence] = skóre tvárí (návrhy), ak je známe.
    @WorkerThread
    fun move(
        context: Context,
        faceIds: List<Long>,
        targetId: Long,
        knownFrom: Long? = null,
        source: String = AssignmentSource.MANUAL,
        confidence: Map<Long, Float> = emptyMap(),
    ) {
        if (faceIds.isEmpty()) return
        val previous = previousOwners(context, faceIds, targetId, knownFrom)
        val db = PeopleDatabase.getInstance(context)
        db.runInTransaction {
            val dao = db.PeopleDao()
            dao.assignFaces(faceIds, targetId, System.currentTimeMillis(), source, confidence)
            previous.forEach { (pid, ids) -> ids.forEach { dao.insertCannotLink(CannotLinkEntity(it, pid)) } }
        }
        FamilyRecorder.facesAssigned(context, faceIds, targetId)
        previous.forEach { (pid, ids) -> FamilyRecorder.facesRejected(context, ids, pid) }
    }

    // Nová osoba s tvárami; tváre, ktoré patrili inej osobe, dostanú cannot-link na ňu. Vráti id osoby.
    @WorkerThread
    fun moveToNewPerson(
        context: Context,
        faceIds: List<Long>,
        name: String,
        knownFrom: Long? = null,
        source: String = AssignmentSource.MANUAL,
    ): Long {
        val previous = previousOwners(context, faceIds, null, knownFrom)
        val db = PeopleDatabase.getInstance(context)
        val newId = db.runInTransaction<Long> {
            val dao = db.PeopleDao()
            val id = dao.createPersonWithFaces(name, faceIds, System.currentTimeMillis(), source, emptyMap())
            previous.forEach { (pid, ids) -> ids.forEach { dao.insertCannotLink(CannotLinkEntity(it, pid)) } }
            id
        }
        FamilyRecorder.personCreated(context, newId, faceIds)
        previous.forEach { (pid, ids) -> FamilyRecorder.facesRejected(context, ids, pid) }
        return newId
    }

    // C11 — odpoveď na „Je to [chosen], alebo [other]?“: chosen = null znamená „niekto iný“
    @WorkerThread
    fun answer(context: Context, faceId: Long, chosen: Long?, others: List<Long>) {
        val db = PeopleDatabase.getInstance(context)
        val rejected = others.filter { it != chosen }
        db.runInTransaction {
            val dao = db.PeopleDao()
            if (chosen != null) {
                dao.assignFaces(listOf(faceId), chosen, System.currentTimeMillis(), AssignmentSource.MANUAL, emptyMap())
            }
            rejected.forEach { dao.rejectFacesForPerson(listOf(faceId), it) }
        }
        if (chosen != null) FamilyRecorder.facesAssigned(context, listOf(faceId), chosen)
        rejected.forEach { FamilyRecorder.facesRejected(context, listOf(faceId), it) }
    }

    // osoba → jej tváre z výberu, ktoré jej doteraz patrili (okrem cieľovej osoby)
    @WorkerThread
    private fun previousOwners(
        context: Context,
        faceIds: List<Long>,
        targetId: Long?,
        knownFrom: Long?,
    ): Map<Long, List<Long>> {
        val wanted = faceIds.toHashSet()
        val owner = HashMap<Long, Long>()
        knownFrom?.let { from -> faceIds.forEach { owner[it] = from } }
        val snapshot = PeopleIndex.get(context)
        faceIds.forEach { id ->
            val o = snapshot.ownerOf(id) ?: return@forEach
            if (o.state != PeopleIndex.State.MANUAL) owner.putIfAbsent(id, o.personId)
        }
        // ručné priradenia sú autoritatívne (snímok môže byť starší)
        PeopleDatabase.getInstance(context).PeopleDao().getAssignments()
            .filter { wanted.contains(it.faceId) }
            .forEach { owner[it.faceId] = it.personId }
        return owner.entries.filter { it.value != targetId }.groupBy({ it.value }, { it.key })
    }
}
