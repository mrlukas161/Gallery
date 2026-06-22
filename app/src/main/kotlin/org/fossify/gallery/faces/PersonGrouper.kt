package org.fossify.gallery.faces

import kotlin.math.sqrt

// Picasa-štýl zoskupovanie:
//  1) potvrdené osoby (people.db) dostanú svoje ručne priradené tváre → z nich centroid (vzor)
//  2) nepriradené tváre sa NAVRHNÚ k najbližšej potvrdenej osobe (ak prah a nie je cannot-link)
//  3) zvyšok sa auto-zoskupí do návrhov
// Čím viac ručných potvrdení, tým ostrejší centroid → tým lepšie návrhy = učenie.
object PersonGrouper {
    private const val ATTACH_THRESHOLD = 0.55f

    data class Grouped(
        val confirmed: List<Person>,    // pomenované osoby (aj s 0/1 tvárou)
        val suggestions: List<Person>,  // nepotvrdené auto-skupiny (>= 2 tváre)
    )

    fun build(
        faces: List<FaceEntity>,
        persons: List<PersonEntity>,
        assignments: List<FaceAssignmentEntity>,
        cannotLinks: List<CannotLinkEntity>,
    ): Grouped {
        val assignedPersonByFace = HashMap<Long, Long>()
        val manualFaces = HashSet<Long>()
        for (a in assignments) {
            assignedPersonByFace[a.faceId] = a.personId
            if (a.isManual) manualFaces.add(a.faceId)
        }
        val cannotPairs = HashSet<Long>() // zakódované faceId,personId
        for (c in cannotLinks) cannotPairs.add(pairKey(c.faceId, c.personId))

        val personFaces = HashMap<Long, ArrayList<FaceEntity>>()
        for (p in persons) personFaces[p.id] = ArrayList()
        val unassigned = ArrayList<FaceEntity>()
        for (f in faces) {
            val id = f.id ?: continue
            val pid = assignedPersonByFace[id]
            if (pid != null && personFaces.containsKey(pid)) {
                personFaces[pid]!!.add(f)
            } else {
                unassigned.add(f)
            }
        }

        // centroid každej osoby = priemer jej RUČNE potvrdených tvárí (ak žiadne, tak zo všetkých jej tvárí)
        val centroids = HashMap<Long, FloatArray>()
        for (p in persons) {
            val fs = personFaces[p.id] ?: continue
            val manual = fs.filter { f -> f.id?.let { id -> manualFaces.contains(id) } == true }
            val base = manual.ifEmpty { fs }
            val c = meanEmbedding(base) ?: continue
            centroids[p.id] = c
        }

        // navrhni nepriradené tváre k najbližšej potvrdenej osobe
        val stillUnassigned = ArrayList<FaceEntity>()
        for (f in unassigned) {
            val id = f.id
            val emb = f.embedding?.let { FaceEmbedder.toFloats(it) }
            if (id == null || emb == null || emb.isEmpty()) {
                stillUnassigned.add(f)
                continue
            }
            var bestPid = -1L
            var bestSim = ATTACH_THRESHOLD
            for ((pid, c) in centroids) {
                if (cannotPairs.contains(pairKey(id, pid))) continue
                val sim = dot(emb, c)
                if (sim > bestSim) {
                    bestSim = sim
                    bestPid = pid
                }
            }
            if (bestPid >= 0) personFaces[bestPid]!!.add(f) else stillUnassigned.add(f)
        }

        val confirmed = persons.map { p ->
            val fs = (personFaces[p.id] ?: arrayListOf()).sortedByDescending { it.score }
            val manualIds = fs.mapNotNull { it.id }.filter { manualFaces.contains(it) }.toSet()
            Person(p.id, p.name, fs, manualIds)
        }.sortedWith(compareBy(nullsLast<String>()) { it.name?.lowercase() })

        val suggestions = FaceClusterer.clusterGroups(stillUnassigned)
            .map { group -> Person(null, null, group.sortedByDescending { it.score }) }
            .filter { it.faceCount >= 2 }
            .sortedByDescending { it.faceCount }

        return Grouped(confirmed, suggestions)
    }

    private fun pairKey(faceId: Long, personId: Long): Long = faceId * 1_000_003L + personId

    private fun meanEmbedding(faces: List<FaceEntity>): FloatArray? {
        var acc: FloatArray? = null
        var n = 0
        for (f in faces) {
            val e = f.embedding?.let { FaceEmbedder.toFloats(it) } ?: continue
            if (e.isEmpty()) continue
            if (acc == null) acc = FloatArray(e.size)
            if (acc.size != e.size) continue
            for (i in e.indices) acc[i] += e[i]
            n++
        }
        if (acc == null || n == 0) return null
        for (i in acc.indices) acc[i] /= n
        normalize(acc)
        return acc
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s).coerceAtLeast(1e-9f)
        for (i in v.indices) v[i] = v[i] / norm
    }
}
