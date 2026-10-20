package org.fossify.gallery.family

import android.content.Context
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.PeopleDatabase

// Odtlačky osôb (AnchorsSet) sa posielajú lenivo: zmena tvárí osobu len označí a odtlačky sa vyberú
// (AnchorPicker, ≤ 20) až pred odovzdaním operácií druhému telefónu (FamilySync.opsSince).
// Zoznam označených osôb je v tabuľke meta (bez zmeny schémy). Volá sa len z pozadia.
class AnchorOutbox(context: Context, private val recorder: OpRecorder) {
    private val app = context.applicationContext
    private val meta = FamilyDatabase.getInstance(app).metaDao()

    fun markDirty(localIds: Collection<Long>) = synchronized(FamilyLock) {
        save(dirtyIds() + localIds)
    }

    fun flush() {
        val dirty = dirtyIds()
        if (dirty.isEmpty()) return
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val faceDao = FacesDatabase.getInstance(app).FaceDao()
        val scope = IndexScope(app)
        val manual = people.getAssignments().filter { it.isManual }.groupBy({ it.personId }, { it.faceId })
        val anchors = people.getAllAnchors()
        for (localId in dirty) {
            val uuid = recorder.existingUuid(localId) ?: continue
            // len tváre z povolených priečinkov (spec 2.5) + vlastné vzory (napr. Picasa), nie prijaté z rodiny
            val own = faceDao.getFacesByIds(manual[localId].orEmpty())
                .filter { scope.allows(it.mediaFullPath) }
                .mapNotNull { f -> f.embedding?.let { FaceEmbedder.toFloats(it) } }
            val extra = anchors
                .filter { it.personId == localId && !it.source.startsWith(OpApplier.ANCHOR_SOURCE_PREFIX) }
                .map { FaceEmbedder.toFloats(it.embedding) }
            val picked = AnchorPicker.pick(own + extra)
            if (picked.isNotEmpty()) recorder.append(AnchorsSet(uuid, FaceEmbedder.MODEL_ID, picked))
        }
        // medzičasom označené osoby ostanú na ďalšie odovzdanie
        synchronized(FamilyLock) { save(dirtyIds() - dirty) }
    }

    private fun save(ids: Set<Long>) = meta.put(MetaEntity(KEY_ANCHORS_DIRTY, ids.joinToString(",")))

    private fun dirtyIds(): Set<Long> = meta.get(KEY_ANCHORS_DIRTY).orEmpty()
        .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    companion object {
        // kľúč v tabuľke meta: osoby, ktorých odtlačky treba znova poslať
        const val KEY_ANCHORS_DIRTY = "f3_anchors_dirty"
    }
}
