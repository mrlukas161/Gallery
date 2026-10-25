package org.fossify.gallery.helpers

import android.content.Context
import android.database.SQLException
import androidx.annotation.WorkerThread
import org.fossify.gallery.faces.AssignmentSource
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.GroupMemberEntity
import org.fossify.gallery.faces.MergeLogCodec
import org.fossify.gallery.faces.MergeLogEntity
import org.fossify.gallery.faces.MergeUndo
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.family.FamilyRecorder

// E2 — zlúčenie osôb so záznamom (merge_log) a jeho vrátenie. Zlúčenie aj vrátenie sú v people.db jedna
// transakcia (PeopleDao.mergeManyInto / undoMerge); skupiny v extras.db sa menia hneď potom (iná DB, nedá
// sa to spojiť do jednej transakcie — skupiny sú vedľajšie, rovnako ako doteraz).
// Rodinné prepojenie: zlúčenie odchádza ako doteraz (PersonMerge, na druhom telefóne len návrh). Vrátenie sa
// nedá poslať ako „zrušenie zlúčenia“ (taká operácia v op-logu nie je a druhý telefón zlúčenie mohol, ale
// nemusel prijať), preto sa vrátené vlastné ručné priradenia pošlú cez FamilyRecorder.facesAssigned pod
// pôvodnou osobou — druhý telefón ich prijme podľa svojho stavu (pri inom priradení vznikne návrh).
// Volať LEN z pozadia.
object PersonMerges {
    // koľko posledných zlúčení ponúknuť na vrátenie
    const val RECENT_LIMIT = 10

    sealed interface UndoResult {
        // vrátené; skipped = tváre, ktoré medzičasom dostala iná osoba (ostali tam)
        data class Done(val personName: String?, val faces: Int, val skipped: Int) : UndoResult

        data class Refused(val reason: MergeUndo.Blocked) : UndoResult

        data object Failed : UndoResult
    }

    // Zlúči [fromIds] do [to] (so záznamom na vrátenie). false = chyba DB, nič sa nezlúčilo.
    @WorkerThread
    fun merge(context: Context, fromIds: List<Long>, to: Long): Boolean {
        val groups = try {
            groupsBefore(context, fromIds, to)
        } catch (ignored: SQLException) {
            emptyMap() // skupiny sú vedľajšie — zlúčenie bez nich (vrátenie potom skupiny neobnoví)
        }
        try {
            PeopleDatabase.getInstance(context).PeopleDao()
                .mergeManyInto(fromIds, to, System.currentTimeMillis(), groups)
        } catch (ignored: SQLException) {
            return false
        } catch (ignored: IllegalStateException) {
            return false
        }
        try {
            val extras = ExtrasDatabase.getInstance(context).ExtrasDao()
            fromIds.forEach { extras.moveMemberships(it, to) }
        } catch (ignored: SQLException) {
            // skupiny sú vedľajšie — zlúčenie osôb už prebehlo
        }
        return true
    }

    @WorkerThread
    fun recent(context: Context): List<MergeLogEntity> = try {
        PeopleDatabase.getInstance(context).PeopleDao().getRecentMerges(RECENT_LIMIT)
    } catch (ignored: SQLException) {
        emptyList()
    }

    @WorkerThread
    fun undo(context: Context, logId: Long): UndoResult {
        val outcome = try {
            PeopleDatabase.getInstance(context).PeopleDao().undoMerge(logId, System.currentTimeMillis())
        } catch (ignored: SQLException) {
            null
        } catch (ignored: IllegalStateException) {
            null
        }
        return when (outcome) {
            null -> UndoResult.Failed
            is MergeUndo.Outcome.Refused -> UndoResult.Refused(outcome.reason)
            is MergeUndo.Outcome.Ready -> {
                val plan = outcome.plan
                restoreGroups(context, plan)
                val own = plan.assignments
                    .filter { it.isManual && it.source != AssignmentSource.FAMILY }
                    .map { it.faceId }
                PeopleIndex.invalidate()
                if (own.isNotEmpty()) FamilyRecorder.facesAssigned(context, own, plan.personId)
                UndoResult.Done(plan.name, plan.assignments.size, plan.skipped)
            }
        }
    }

    // skupiny každej zlúčenej osoby a tie, do ktorých sa cieľová osoba dostane až zlúčením
    private fun groupsBefore(context: Context, fromIds: List<Long>, to: Long): Map<Long, String> {
        val extras = ExtrasDatabase.getInstance(context).ExtrasDao()
        val target = extras.getGroupsForPerson(to).toHashSet()
        val out = HashMap<Long, String>()
        for (from in fromIds) {
            val groups = extras.getGroupsForPerson(from)
            val added = groups.filter { it !in target }
            out[from] = MergeLogCodec.encodeGroups(MergeLogCodec.Groups(groups, added))
            target.addAll(groups)
        }
        return out
    }

    private fun restoreGroups(context: Context, plan: MergeUndo.Plan) {
        if (plan.groupsAdd.isEmpty() && plan.groupsRemoveTarget.isEmpty()) return
        try {
            val extras = ExtrasDatabase.getInstance(context).ExtrasDao()
            val existing = extras.getGroups().mapTo(HashSet()) { it.id }
            plan.groupsAdd.filter { it in existing }.forEach { extras.addMember(GroupMemberEntity(it, plan.personId)) }
            plan.groupsRemoveTarget.forEach { extras.removeMember(it, plan.targetId) }
        } catch (ignored: SQLException) {
            // skupiny sú vedľajšie — osoba a jej tváre sú už späť
        }
    }
}
