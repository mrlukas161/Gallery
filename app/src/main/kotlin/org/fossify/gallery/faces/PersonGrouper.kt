package org.fossify.gallery.faces

import kotlin.math.min
import kotlin.math.sqrt

// Po spätnej väzbe (auto-domiešavanie = blud): osoba = LEN ručne potvrdené tváre.
// Návrhy sa počítajú samostatne (FaceTaggingActivity) a používateľ ich potvrdzuje/odmieta → učenie.
// Čistá logika bez Android tried (testy v PersonGrouperTest).
// Jedno miesto všetkých pravidiel zaraďovania (Ľudia, Návrhy, Osoba) — preto veľa malých funkcií.
@Suppress("TooManyFunctions")
object PersonGrouper {

    // C3 — rozostup od druhej najbližšej osoby. Tvár sa zaradí automaticky len vtedy, keď je najlepšia osoba
    // aspoň o toľko bližšie než druhá najlepšia. Súrodenci a rodič s dieťaťom majú ťažiská blízko seba
    // (kosínus 0,75–0,85) a ich tváre ležia „medzi" nimi s rozdielom pod 0,05; pri 0,07 takáto tvár ostane
    // neoznačená (dá sa potvrdiť v Návrhoch) namiesto toho, aby sa potichu zaradila k nesprávnemu.
    // Rozsah z analýzy 0,06–0,08; vyššie číslo = menej automatických zaradení.
    const val AUTO_MARGIN = 0.07f

    // C5/D7 — váha priradení s is_manual=false (prišli z rodinného prepojenia) a vzorov zo zdroja „family:".
    // Ich súčet váh navyše nesmie prevýšiť počet vlastných potvrdených tvárí a vzorov (familyWeight).
    const val FAMILY_WEIGHT = 0.5f

    // rovnaké ako OpApplier.ANCHOR_SOURCE_PREFIX (balík family/ sa tu len číta)
    const val FAMILY_SOURCE_PREFIX = "family:"

    // prah, nad ktorým sa vektor pridá do existujúceho pod-ťažiska (inak založí nové)
    const val SUB_CENTROID_THRESHOLD = 0.6f

    private const val UNROLL = 4

    // vzory osoby (anchor_embeddings) rozdelené podľa pôvodu
    class Anchors(val own: List<FloatArray>, val family: List<FloatArray>)

    // vektory osoby pre ťažisko: vlastné (váha 1) a rodinné (znížená váha)
    class Evidence(val own: List<FloatArray>, val family: List<FloatArray>)

    fun isFamilySource(source: String): Boolean = source.startsWith(FAMILY_SOURCE_PREFIX)

    fun splitAnchors(anchors: List<AnchorEmbeddingEntity>, decode: (ByteArray) -> FloatArray): Map<Long, Anchors> {
        val own = HashMap<Long, ArrayList<FloatArray>>()
        val family = HashMap<Long, ArrayList<FloatArray>>()
        for (a in anchors) {
            val target = if (isFamilySource(a.source)) family else own
            target.getOrPut(a.personId) { ArrayList() }.add(decode(a.embedding))
        }
        return (own.keys + family.keys).associateWith { Anchors(own[it].orEmpty(), family[it].orEmpty()) }
    }

    // Osoby z priradení. Tváre sa tu NEfiltrujú podľa skóre ani veľkosti (FaceFilter) — ručné potvrdenie
    // má prednosť, inak by potvrdená tvár z osoby v Ľuďoch zmizla.
    fun confirmedPersons(
        faces: List<FaceEntity>,
        persons: List<PersonEntity>,
        assignments: List<FaceAssignmentEntity>,
    ): List<Person> {
        val byFace = HashMap<Long, FaceAssignmentEntity>()
        for (a in assignments) byFace[a.faceId] = a

        val byPerson = HashMap<Long, ArrayList<FaceEntity>>()
        for (p in persons) byPerson[p.id] = ArrayList()
        for (f in faces) {
            val id = f.id ?: continue
            val a = byFace[id] ?: continue
            byPerson[a.personId]?.add(f)
        }
        return persons.map { p ->
            val fs = (byPerson[p.id] ?: arrayListOf()).sortedByDescending { it.score }
            val ids = fs.mapNotNull { it.id }.toSet()
            val family = ids.filterTo(HashSet()) { byFace[it]?.isManual == false }
            Person(p.id, p.name, fs, ids, family)
        }.sortedWith(personOrder)
    }

    // priorita: najprv osoby s najviac fotkami, potom abecedne
    private val personOrder = compareByDescending<Person> { it.faceCount }.thenBy { (it.name ?: "").lowercase() }

    // Vektory osoby: vlastné = ručne potvrdené tváre (is_manual=true) + vlastné vzory (Picasa, záloha),
    // rodinné = priradenia s is_manual=false + vzory „family:". Auto-zaradené tváre sa nerátajú nikdy.
    fun evidenceOf(person: Person, anchors: Anchors?, decode: (ByteArray) -> FloatArray): Evidence {
        val own = ArrayList<FloatArray>()
        val family = ArrayList<FloatArray>()
        for (f in person.faces) {
            val id = f.id ?: continue
            if (!person.manualFaceIds.contains(id)) continue
            val e = f.embedding ?: continue
            if (person.familyFaceIds.contains(id)) family.add(decode(e)) else own.add(decode(e))
        }
        anchors?.let {
            own.addAll(it.own)
            family.addAll(it.family)
        }
        return Evidence(own, family)
    }

    // váha jedného rodinného vektora: najviac FAMILY_WEIGHT a spolu najviac toľko, koľko je vlastných
    fun familyWeight(ownCount: Int, familyCount: Int): Float =
        if (familyCount == 0) 0f else min(FAMILY_WEIGHT, ownCount.toFloat() / familyCount)

    // Ťažisko pre automatické zaradenie. Bez vlastného vektora žiadne — osoba len z rodinného prepojenia
    // sa automaticky nedopĺňa (chyba z druhého telefónu by sa šírila), návrhy dostane (suggestionCentroids).
    fun autoCentroid(ev: Evidence): FloatArray? {
        if (ev.own.isEmpty()) return null
        return weightedCentroid(ev.own, ev.family, familyWeight(ev.own.size, ev.family.size))
    }

    // Ťažisko, ktoré slúži len ako súper pri teste rozostupu (aj osoba len z rodiny „berie" podobné tváre).
    private fun competitorCentroid(ev: Evidence): FloatArray? = autoCentroid(ev) ?: centroidOf(ev.family)

    // Ťažiská pre Návrhy (FaceTagging). Rodinné vektory sa len pridávajú k pod-ťažiskám z vlastných tvárí
    // a nezakladajú vlastné. Osoba bez vlastných vektorov dostane jedno ťažisko z rodinných.
    fun suggestionCentroids(ev: Evidence): List<FloatArray> = if (ev.own.isEmpty()) {
        listOfNotNull(centroidOf(ev.family))
    } else {
        subCentroidsWeighted(ev.own, ev.family, familyWeight(ev.own.size, ev.family.size))
    }

    // Automatické zaradenie do albumu osoby od zvolenej ISTOTY (podobnosť k ťažisku). Zaradené tváre sú
    // „nepotvrdené" (nie sú v manualFaceIds), dajú sa kedykoľvek opraviť cez „Toto nie je on". Pravidlá:
    //  - ignorované tváre (extras.db) a cannot-links sa nezaraďujú (C1);
    //  - best ≥ threshold a best − druhá najlepšia osoba ≥ margin (C3);
    //  - jedna osoba najviac raz na fotke: tváre fotky sa priraďujú greedy podľa skóre a osoba, ktorá je
    //    na fotke už priradená (ručne alebo z rodiny), sa pre ostatné tváre fotky vôbec neuvažuje (C4).
    //    Tvár, ktorej najlepšiu osobu na fotke získala lepšia tvár, ostane nezaradená (nepresúva sa
    //    k druhej najlepšej — tá rozostup aj tak nesplnila);
    //  - ťažisko len z ručne potvrdených tvárí a vzorov, rodinné so zníženou váhou (C5).
    fun withAutoMatches(
        persons: List<Person>,
        allFaces: List<FaceEntity>,
        anchorsByPerson: Map<Long, Anchors>,
        cannotLinks: List<CannotLinkEntity>,
        ignoredFaceIds: Set<Long>,
        threshold: Float,
        margin: Float = AUTO_MARGIN,
        decode: (ByteArray) -> FloatArray = { FaceEmbedder.toFloats(it) },
    ): List<Person> {
        if (threshold <= 0f || persons.isEmpty()) return persons
        val model = buildModel(persons, anchorsByPerson, decode) ?: return persons
        val banned = HashMap<Long, HashSet<Long>>()
        cannotLinks.forEach { banned.getOrPut(it.faceId) { HashSet() }.add(it.personId) }
        val scorer = Scorer(model, Fixed(persons), banned, ignoredFaceIds, decode)

        // kandidáti (tvár → jej najlepšia osoba) zoskupení podľa fotky
        val byPhoto = HashMap<String, ArrayList<Candidate>>()
        for (f in allFaces) {
            val c = scorer.best(f) ?: continue
            if (c.score >= threshold && c.score - c.second >= margin) {
                byPhoto.getOrPut(f.mediaFullPath) { ArrayList() }.add(Candidate(f, c.personId, c.score))
            }
        }
        val extra = pickPerPhoto(byPhoto)
        if (extra.isEmpty()) return persons
        return persons.map { p ->
            val add = p.id?.let { extra[it] } ?: return@map p
            if (add.isEmpty()) p else p.copy(faces = (p.faces + add).sortedByDescending { it.score })
        }.sortedWith(personOrder)
    }

    // najlepšia osoba pre ešte nepriradenú, neignorovanú tvár (bez osôb zakázaných cannot-linkom a osôb,
    // ktoré na fotke už sú)
    private class Scorer(
        val model: Model,
        val fixed: Fixed,
        val banned: Map<Long, Set<Long>>,
        val ignored: Set<Long>,
        val decode: (ByteArray) -> FloatArray,
    ) {
        fun best(f: FaceEntity): Best? {
            val fid = f.id ?: return null
            val e = f.embedding ?: return null
            if (fixed.assigned.contains(fid) || ignored.contains(fid)) return null
            return model.best(decode(e), banned[fid], fixed.onPhoto[f.mediaFullPath])
        }
    }

    // už priradené tváre a osoby, ktoré na fotke už sú (ručne alebo z rodiny) — tie automatika nemení
    private class Fixed(persons: List<Person>) {
        val assigned = HashSet<Long>()
        val onPhoto = HashMap<String, HashSet<Long>>()

        init {
            for (p in persons) {
                val pid = p.id ?: continue
                for (f in p.faces) {
                    f.id?.let { assigned.add(it) }
                    onPhoto.getOrPut(f.mediaFullPath) { HashSet() }.add(pid)
                }
            }
        }
    }

    // greedy na fotku: najlepšie skóre vyhráva, každá osoba najviac raz
    private fun pickPerPhoto(byPhoto: Map<String, List<Candidate>>): Map<Long, List<FaceEntity>> {
        val extra = HashMap<Long, ArrayList<FaceEntity>>()
        for (cands in byPhoto.values) {
            val used = HashSet<Long>()
            for (c in cands.sortedByDescending { it.score }) {
                if (used.add(c.personId)) extra.getOrPut(c.personId) { ArrayList() }.add(c.face)
            }
        }
        return extra
    }

    private class Candidate(val face: FaceEntity, val personId: Long, val score: Float)

    private class Best(val personId: Long, val score: Float, val second: Float)

    // Ťažiská všetkých osôb v jednom súvislom poli — vnútorná slučka (10 000 tvárí × 100 osôb) bez alokácií.
    private class Model(val ids: LongArray, val assignable: BooleanArray, val flat: FloatArray, val dim: Int) {
        fun best(v: FloatArray, banned: Set<Long>?, blocked: Set<Long>?): Best? {
            if (v.size != dim) return null
            var bestIdx = -1
            var best = Float.NEGATIVE_INFINITY
            var second = Float.NEGATIVE_INFINITY
            for (p in ids.indices) {
                val pid = ids[p]
                if (banned?.contains(pid) == true || blocked?.contains(pid) == true) continue
                val s = dot(v, flat, p * dim)
                if (s > best) {
                    second = best
                    best = s
                    bestIdx = p
                } else if (s > second) {
                    second = s
                }
            }
            if (bestIdx < 0 || !assignable[bestIdx]) return null
            return Best(ids[bestIdx], best, second)
        }

        // štyri nezávislé súčty — JIT/ART ich počíta súbežne (cca 2× rýchlejšie než jeden reťazec súčtov)
        private fun dot(v: FloatArray, flat: FloatArray, off: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var i = 0
            val end4 = dim - dim % UNROLL
            while (i < end4) {
                val i1 = i + 1
                val i2 = i1 + 1
                val i3 = i2 + 1
                s0 += v[i] * flat[off + i]
                s1 += v[i1] * flat[off + i1]
                s2 += v[i2] * flat[off + i2]
                s3 += v[i3] * flat[off + i3]
                i += UNROLL
            }
            while (i < dim) {
                s0 += v[i] * flat[off + i]
                i++
            }
            return (s0 + s1) + (s2 + s3)
        }
    }

    private fun buildModel(
        persons: List<Person>,
        anchorsByPerson: Map<Long, Anchors>,
        decode: (ByteArray) -> FloatArray,
    ): Model? {
        val ids = ArrayList<Long>()
        val assignable = ArrayList<Boolean>()
        val cents = ArrayList<FloatArray>()
        for (p in persons) {
            val pid = p.id ?: continue
            val ev = evidenceOf(p, anchorsByPerson[pid], decode)
            val c = competitorCentroid(ev) ?: continue
            if (cents.isNotEmpty() && c.size != cents[0].size) continue
            ids.add(pid)
            assignable.add(ev.own.isNotEmpty())
            cents.add(c)
        }
        if (cents.isEmpty() || !assignable.contains(true)) return null
        val dim = cents[0].size
        val flat = FloatArray(cents.size * dim)
        cents.forEachIndexed { i, c -> c.copyInto(flat, i * dim) }
        return Model(ids.toLongArray(), assignable.toBooleanArray(), flat, dim)
    }

    // Osoba v UI podľa viditeľnosti tvárí (FaceVisibility). Ak sú skryté všetky jej tváre:
    //  - aspoň jedna leží v zamknutom priečinku → osoba sa neukáže vôbec (súkromie, rozhodol Lukáš 20.10.);
    //  - inak (fotky sa nenašli / sú mimo rozsahu) → osoba ostane, bez tvárí, so značkou photosMissing.
    // Osoba, ktorá tváre nemala ani predtým (napr. len so vzormi z Picasy), sa správa ako doteraz.
    fun visiblePerson(person: Person, isVisible: (String) -> Boolean, isLocked: (String) -> Boolean): Person? {
        val visible = person.faces.filter { isVisible(it.mediaFullPath) }
        return when {
            visible.size == person.faces.size -> person
            visible.isEmpty() -> if (person.faces.any { isLocked(it.mediaFullPath) }) {
                null
            } else {
                person.copy(
                    faces = emptyList(),
                    manualFaceIds = emptySet(),
                    familyFaceIds = emptySet(),
                    photosMissing = true,
                )
            }

            else -> {
                val visibleIds = visible.mapNotNullTo(HashSet()) { it.id }
                person.copy(
                    faces = visible,
                    manualFaceIds = person.manualFaceIds.intersect(visibleIds),
                    familyFaceIds = person.familyFaceIds.intersect(visibleIds),
                )
            }
        }
    }

    // C4 — fotky, na ktorých by osoba mala po ručnom priradení [selected] viac ako jednu tvár
    // ([personFaces] = tváre, ktoré už osobe patria). Slúži len na upozornenie, nie na zákaz.
    fun samePhotoConflicts(selected: List<FaceEntity>, personFaces: List<FaceEntity>): Set<String> {
        val byPath = HashMap<String, HashSet<Long>>()
        for (f in personFaces + selected) {
            val id = f.id ?: continue
            byPath.getOrPut(f.mediaFullPath) { HashSet() }.add(id)
        }
        val selectedPaths = selected.mapTo(HashSet()) { it.mediaFullPath }
        return byPath.filter { (path, ids) -> ids.size > 1 && selectedPaths.contains(path) }.keys
    }

    // centroid osoby = priemer vektorov, L2-normalizovaný
    fun centroidOf(embs: List<FloatArray>): FloatArray? = weightedCentroid(embs, emptyList(), 0f)

    // vlastné vektory s váhou 1 ⊕ rodinné s váhou [familyWeight], L2-normalizovaný
    fun weightedCentroid(own: List<FloatArray>, family: List<FloatArray>, familyWeight: Float): FloatArray? {
        var acc: FloatArray? = null
        var n = 0
        fun add(e: FloatArray, w: Float) {
            if (e.isEmpty() || w <= 0f) return
            val a = acc ?: FloatArray(e.size).also { acc = it }
            if (a.size != e.size) return
            for (i in e.indices) a[i] += e[i] * w
            n++
        }
        own.forEach { add(it, 1f) }
        family.forEach { add(it, familyWeight) }
        val result = acc
        if (result == null || n == 0) return null
        normalize(result)
        return result
    }

    // Viac ťažísk na osobu: jej tváre sa zhluknú do podskupín (okuliare/bez, starnutie, deti v čase).
    // Kandidát sa potom porovná s NAJBLIŽŠÍM ťažiskom (nie s jedným spriemerovaným) → lepšie návrhy.
    fun subCentroids(embs: List<FloatArray>, threshold: Float = SUB_CENTROID_THRESHOLD): List<FloatArray> =
        subCentroidsWeighted(embs, emptyList(), 0f, threshold)

    // Pod-ťažiská zakladajú LEN vlastné vektory. Rodinný vektor sa s váhou [familyWeight] pridá do najbližšieho
    // pod-ťažiska nad prahom, inak sa zahodí (nesmie vytvoriť samostatnú podskupinu — C5/D7).
    fun subCentroidsWeighted(
        own: List<FloatArray>,
        family: List<FloatArray>,
        familyWeight: Float,
        threshold: Float = SUB_CENTROID_THRESHOLD,
    ): List<FloatArray> {
        val clusters = Clusters(threshold)
        own.forEach { clusters.add(it, 1f, mayCreate = true) }
        if (familyWeight > 0f) family.forEach { clusters.add(it, familyWeight, mayCreate = false) }
        return clusters.centroids
    }

    private class Clusters(private val threshold: Float) {
        private val sums = ArrayList<FloatArray>()
        val centroids = ArrayList<FloatArray>()

        fun add(e: FloatArray, weight: Float, mayCreate: Boolean) {
            if (e.isEmpty() || (centroids.isNotEmpty() && e.size != centroids[0].size)) return
            val i = nearest(e)
            when {
                i >= 0 -> {
                    val sum = sums[i]
                    for (j in sum.indices) sum[j] += e[j] * weight
                    centroids[i] = sum.copyOf().also { normalize(it) }
                }

                mayCreate -> {
                    sums.add(FloatArray(e.size) { e[it] * weight })
                    centroids.add(e.copyOf().also { normalize(it) })
                }
            }
        }

        private fun nearest(e: FloatArray): Int {
            var best = -1
            var bestSim = threshold
            for (i in centroids.indices) {
                val s = cosine(e, centroids[i])
                if (s > bestSim) {
                    bestSim = s
                    best = i
                }
            }
            return best
        }
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
