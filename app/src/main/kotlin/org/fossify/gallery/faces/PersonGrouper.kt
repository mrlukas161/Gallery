package org.fossify.gallery.faces

import kotlin.math.sqrt

// Po spätnej väzbe (auto-domiešavanie = blud): osoba = LEN ručne potvrdené tváre.
// Návrhy sa počítajú samostatne (FaceTaggingActivity) a používateľ ich potvrdzuje/odmieta → učenie.
object PersonGrouper {

    fun confirmedPersons(
        faces: List<FaceEntity>,
        persons: List<PersonEntity>,
        assignments: List<FaceAssignmentEntity>,
    ): List<Person> {
        val personIdByFace = HashMap<Long, Long>()
        for (a in assignments) personIdByFace[a.faceId] = a.personId

        val byPerson = HashMap<Long, ArrayList<FaceEntity>>()
        for (p in persons) byPerson[p.id] = ArrayList()
        for (f in faces) {
            val id = f.id ?: continue
            val pid = personIdByFace[id] ?: continue
            byPerson[pid]?.add(f)
        }
        return persons.map { p ->
            val fs = (byPerson[p.id] ?: arrayListOf()).sortedByDescending { it.score }
            val ids = fs.mapNotNull { it.id }.toSet()
            Person(p.id, p.name, fs, ids) // všetky priradené tváre sú "potvrdené"
        }.sortedWith(
            // priorita: najprv osoby s najviac fotkami, potom abecedne
            compareByDescending<Person> { it.faceCount }.thenBy { (it.name ?: "").lowercase() }
        )
    }

    // centroid osoby = priemer (potvrdené tváre ⊕ Picasa anchory), L2-normalizovaný
    fun centroidOf(embs: List<FloatArray>): FloatArray? {
        var acc: FloatArray? = null
        var n = 0
        for (e in embs) {
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

    // kosínus medzi L2-normalizovanými vektormi = dot product
    fun cosine(a: FloatArray, b: FloatArray): Float {
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
