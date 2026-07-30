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

    // Automatické zaradenie do albumu osoby od zvolenej ISTOTY (podobnosť k centroidu).
    // Zaradené tváre sú „nepotvrdené" (nie sú v manualFaceIds), takže sa dajú kedykoľvek opraviť
    // cez „Toto nie je on". Rešpektuje cannot-links a každú tvár priradí len NAJLEPŠEJ osobe.
    fun withAutoMatches(
        persons: List<Person>,
        allFaces: List<FaceEntity>,
        anchorsByPerson: Map<Long, List<FloatArray>>,
        cannotLinks: List<CannotLinkEntity>,
        threshold: Float,
    ): List<Person> {
        if (threshold <= 0f || persons.isEmpty()) return persons
        val assigned = HashSet<Long>()
        persons.forEach { p -> p.faces.forEach { f -> f.id?.let { assigned.add(it) } } }
        val banned = HashSet<String>()
        cannotLinks.forEach { banned.add("${it.faceId}|${it.personId}") }

        // centroid osoby z potvrdených tvárí + Picasa vzorov
        val centroids = HashMap<Long, FloatArray>()
        for (p in persons) {
            val pid = p.id ?: continue
            val embs = ArrayList<FloatArray>()
            p.faces.forEach { f -> f.embedding?.let { embs.add(FaceEmbedder.toFloats(it)) } }
            anchorsByPerson[pid]?.let { embs.addAll(it) }
            centroidOf(embs)?.let { centroids[pid] = it }
        }
        if (centroids.isEmpty()) return persons

        val extra = HashMap<Long, ArrayList<FaceEntity>>()
        for (f in allFaces) {
            val fid = f.id ?: continue
            if (assigned.contains(fid)) continue
            val e = f.embedding ?: continue
            val v = FaceEmbedder.toFloats(e)
            var bestPid = -1L
            var bestSim = 0f
            for ((pid, c) in centroids) {
                if (banned.contains("$fid|$pid")) continue
                val s = cosine(v, c)
                if (s > bestSim) {
                    bestSim = s
                    bestPid = pid
                }
            }
            if (bestPid > 0 && bestSim >= threshold) {
                extra.getOrPut(bestPid) { ArrayList() }.add(f)
            }
        }
        if (extra.isEmpty()) return persons
        return persons.map { p ->
            val add = p.id?.let { extra[it] } ?: return@map p
            if (add.isEmpty()) p else p.copy(faces = (p.faces + add).sortedByDescending { it.score })
        }.sortedWith(compareByDescending<Person> { it.faceCount }.thenBy { (it.name ?: "").lowercase() })
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

    // Viac ťažísk na osobu: jej tváre sa zhluknú do podskupín (okuliare/bez, starnutie, deti v čase).
    // Kandidát sa potom porovná s NAJBLIŽŠÍM ťažiskom (nie s jedným spriemerovaným) → lepšie návrhy.
    fun subCentroids(embs: List<FloatArray>, threshold: Float = 0.6f): List<FloatArray> {
        val cents = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        for (e in embs) {
            if (e.isEmpty()) continue
            var best = -1
            var bestSim = threshold
            for (i in cents.indices) {
                val s = cosine(e, cents[i])
                if (s > bestSim) {
                    bestSim = s
                    best = i
                }
            }
            if (best >= 0) {
                val c = cents[best]
                val n = counts[best]
                for (j in c.indices) c[j] = (c[j] * n + e[j]) / (n + 1)
                normalize(c)
                counts[best] = n + 1
            } else {
                cents.add(e.copyOf())
                counts.add(1)
            }
        }
        return cents
    }

    fun maxCosine(e: FloatArray, centroids: List<FloatArray>): Float {
        var m = -1f
        for (c in centroids) {
            val s = cosine(e, c)
            if (s > m) m = s
        }
        return m
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
