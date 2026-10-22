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

    // C7 — šírka pásma „neisté, potvrď“ pod prahom automatiky. Pri kNN skóre (C6) leží väčšina správnych,
    // ale automaticky nezaradených tvárí do 0,1 pod prahom; nižšie už prevládajú cudzí ľudia a sekcia
    // „Potvrď“ by sa zahltila.
    const val UNCERTAIN_BAND = 0.10f

    // prah, od ktorého sa počíta pásmo „neisté“, keď je automatika vypnutá (= FaceAuto.NORMAL)
    const val REFERENCE_THRESHOLD = 0.72f

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

    // Pozn. C6: automatika aj Návrhy dnes hodnotia tvár kNN skóre (KnnModel, classify). Ťažiská nižšie
    // ostávajú ako jednoduchý jednopriemerový model (KnnModel z rovnakého váženia robí predvýber osôb).
    // Ťažisko z vlastných vektorov a rodinných so zníženou váhou. Bez vlastného vektora žiadne — osoba len
    // z rodinného prepojenia sa automaticky nedopĺňa (chyba z druhého telefónu by sa šírila).
    fun autoCentroid(ev: Evidence): FloatArray? {
        if (ev.own.isEmpty()) return null
        return weightedCentroid(ev.own, ev.family, familyWeight(ev.own.size, ev.family.size))
    }

    // Pod-ťažiská osoby. Rodinné vektory sa len pridávajú k pod-ťažiskám z vlastných tvárí
    // a nezakladajú vlastné. Osoba bez vlastných vektorov dostane jedno ťažisko z rodinných.
    fun suggestionCentroids(ev: Evidence): List<FloatArray> = if (ev.own.isEmpty()) {
        listOfNotNull(centroidOf(ev.family))
    } else {
        subCentroidsWeighted(ev.own, ev.family, familyWeight(ev.own.size, ev.family.size))
    }

    // Automatické zaradenie do albumu osoby od zvolenej ISTOTY. Zaradené tváre sú „nepotvrdené" (nie sú
    // v manualFaceIds), dajú sa kedykoľvek opraviť cez „Toto nie je on". Pravidlá sú v classify.
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
        return classify(persons, allFaces, anchorsByPerson, cannotLinks, ignoredFaceIds, threshold, margin, decode)
            .persons
    }

    // C7 — tri stavy nepriradenej tváre: AUTOMATICKY (pridá sa do osoby), NEISTÉ (čaká v sekcii „Potvrď“
    // na karte osoby) alebo nič. Skóre je kNN (C6, KnnModel) s penalizáciou za odmietnuté tváre (C8).
    //  - ignorované tváre (extras.db) a cannot-links sa nezaraďujú (C1);
    //  - automaticky: skóre ≥ prah osoby (KnnModel.autoThreshold) a rozostup od druhej osoby ≥ margin (C3);
    //  - neisté: skóre ≥ prah − UNCERTAIN_BAND, ale nie automaticky (pod prahom alebo s malým rozostupom);
    //    pri vypnutej automatike (threshold ≤ 0) sa pásmo počíta od REFERENCE_THRESHOLD a všetko je neisté;
    //  - jedna osoba najviac raz na fotke: tváre fotky sa priraďujú greedy podľa skóre a osoba, ktorá je
    //    na fotke už priradená (ručne alebo z rodiny), sa pre ostatné tváre fotky vôbec neuvažuje (C4).
    //    Neistá tvár sa ponúkne len osobe, ktorá na fotke ešte nie je (ani automaticky), a len raz na fotku;
    //  - prototypy len z ručne potvrdených tvárí a vzorov, rodinné so zníženou váhou (C5).
    // [contested] = tváre, pri ktorých sú dve osoby nad dolnou hranicou pásma (fronta C11).
    @Suppress("LongParameterList")
    fun classify(
        persons: List<Person>,
        pool: List<FaceEntity>,
        anchorsByPerson: Map<Long, Anchors>,
        cannotLinks: List<CannotLinkEntity>,
        ignoredFaceIds: Set<Long>,
        threshold: Float,
        margin: Float = AUTO_MARGIN,
        decode: (ByteArray) -> FloatArray = { FaceEmbedder.toFloats(it) },
        negativeFaces: Map<Long, FaceEntity> = pool.associateByIdNotNull(),
    ): Classification {
        val model = buildKnn(persons, anchorsByPerson, cannotLinks, negativeFaces, decode)
            ?: return Classification(persons, emptyMap(), emptyMap(), emptyList(), null)
        val banned = HashMap<Long, HashSet<Long>>()
        cannotLinks.forEach { banned.getOrPut(it.faceId) { HashSet() }.add(it.personId) }
        val scan = Scan(model, Fixed(persons), banned, ignoredFaceIds, threshold, margin)
        pool.forEach { scan.add(it, decode) }
        val picked = pickPerPhoto(scan.byPhoto)
        val autoOwner = HashMap<Long, Long>()
        picked.auto.forEach { (pid, list) -> list.forEach { s -> s.face.id?.let { autoOwner[it] = pid } } }
        val result = persons.map { p ->
            val add = p.id?.let { picked.auto[it] } ?: return@map p
            if (add.isEmpty()) p else p.copy(faces = (p.faces + add.map { it.face }).sortedByDescending { it.score })
        }.sortedWith(personOrder)
        return Classification(
            result,
            picked.uncertain.mapValues { (_, list) -> list.sortedByDescending { it.score } },
            autoOwner,
            scan.contested.filter { s -> s.face.id?.let { autoOwner.containsKey(it) } != true },
            model,
        )
    }

    // prechod nepriradenými tvárami: kandidáti podľa fotky (pre greedy) a sporné tváre (C11)
    @Suppress("LongParameterList")
    private class Scan(
        val model: KnnModel,
        val fixed: Fixed,
        val banned: Map<Long, Set<Long>>,
        val ignored: Set<Long>,
        val threshold: Float,
        val margin: Float,
    ) {
        private val ranker = model.newRanker()
        private val low = uncertainFloor(threshold)
        val byPhoto = HashMap<String, ArrayList<Candidate>>()
        val contested = ArrayList<Scored>()

        fun add(f: FaceEntity, decode: (ByteArray) -> FloatArray) {
            val fid = f.id ?: return
            val e = f.embedding ?: return
            if (fixed.assigned.contains(fid) || ignored.contains(fid)) return
            val r = ranker.rank(decode(e), banned[fid], fixed.onPhoto[f.mediaFullPath]) ?: return
            if (r.best < low) return
            val scored = Scored(f, r.bestId, r.best, r.secondId, r.second)
            byPhoto.getOrPut(f.mediaFullPath) { ArrayList() }.add(Candidate(scored, isAuto(r)))
            if (r.hasSecond && r.second >= low) contested.add(scored)
        }

        private fun isAuto(r: KnnModel.Ranking): Boolean = threshold > 0f && model.isAssignable(r.bestIndex) &&
            r.best >= model.autoThreshold(r.bestIndex, threshold) && r.margin >= margin
    }

    // dolná hranica pásma „neisté“
    fun uncertainFloor(threshold: Float): Float =
        (if (threshold > 0f) threshold else REFERENCE_THRESHOLD) - UNCERTAIN_BAND

    // kNN model všetkých osôb (C6) vrátane negatívnych prototypov z cannot-links (C8)
    fun buildKnn(
        persons: List<Person>,
        anchorsByPerson: Map<Long, Anchors>,
        cannotLinks: List<CannotLinkEntity>,
        faceById: Map<Long, FaceEntity>,
        decode: (ByteArray) -> FloatArray = { FaceEmbedder.toFloats(it) },
    ): KnnModel? {
        val negByPerson = HashMap<Long, ArrayList<KnnModel.FaceVec>>()
        for (link in cannotLinks) {
            val e = faceById[link.faceId]?.embedding ?: continue
            negByPerson.getOrPut(link.personId) { ArrayList() }.add(KnnModel.FaceVec(link.faceId, decode(e)))
        }
        val inputs = persons.mapNotNull { p ->
            val pid = p.id ?: return@mapNotNull null
            val own = ArrayList<KnnModel.FaceVec>()
            val family = ArrayList<KnnModel.FaceVec>()
            for (f in p.faces) {
                val id = f.id ?: continue
                if (!p.manualFaceIds.contains(id)) continue
                val e = f.embedding ?: continue
                val target = if (p.familyFaceIds.contains(id)) family else own
                target.add(KnnModel.FaceVec(id, decode(e)))
            }
            val anchors = anchorsByPerson[pid]
            KnnModel.PersonInput(
                pid, own, anchors?.own.orEmpty(), family, anchors?.family.orEmpty(), negByPerson[pid].orEmpty(),
            )
        }
        return KnnModel.build(inputs)
    }

    // C11 — fronta „Pomôž mi rozhodnúť“: tváre s najmenším rozostupom medzi dvoma osobami (tam jedna
    // odpoveď rozhodne najviac), pri rovnosti tá s vyšším skóre. [pair] obmedzí frontu na dvojicu osôb
    // (upozornenie z C10), [skipped] = tváre preskočené v tomto sedení.
    fun decisionQueue(
        contested: List<Scored>,
        pair: Pair<Long, Long>? = null,
        skipped: Set<Long> = emptySet(),
        limit: Int = Int.MAX_VALUE,
    ): List<Scored> = contested.asSequence()
        .filter { it.secondId != KnnModel.NO_PERSON && it.face.id?.let { id -> !skipped.contains(id) } == true }
        .filter { s -> pair == null || setOf(s.personId, s.secondId) == setOf(pair.first, pair.second) }
        .sortedWith(compareBy<Scored> { it.margin }.thenByDescending { it.score })
        .take(limit)
        .toList()

    private fun List<FaceEntity>.associateByIdNotNull(): Map<Long, FaceEntity> {
        val m = HashMap<Long, FaceEntity>(size * 2)
        for (f in this) f.id?.let { m[it] = f }
        return m
    }

    // tvár a jej najlepšia (personId) a druhá najlepšia osoba (secondId = KnnModel.NO_PERSON, ak nie je)
    class Scored(val face: FaceEntity, val personId: Long, val score: Float, val secondId: Long, val second: Float) {
        val margin: Float get() = score - second
    }

    // výsledok classify: osoby s automatickými tvárami, neisté tváre podľa osoby, vlastník automatických
    // tvárí (faceId → personId), sporné tváre (C11) a model (Návrhy, odhad presnosti)
    class Classification(
        val persons: List<Person>,
        val uncertain: Map<Long, List<Scored>>,
        val autoOwner: Map<Long, Long>,
        val contested: List<Scored>,
        val model: KnnModel?,
    )

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

    private class Candidate(val scored: Scored, val isAuto: Boolean)

    private class Picked(val auto: Map<Long, List<Scored>>, val uncertain: Map<Long, List<Scored>>)

    // Greedy na fotku: najlepšie skóre vyhráva, každá osoba najviac raz. Najprv automatické, potom neisté
    // (len pre osoby, ktoré na fotke ešte nie sú). Tvár, ktorej osobu získala lepšia tvár, ostane bez stavu.
    private fun pickPerPhoto(byPhoto: Map<String, List<Candidate>>): Picked {
        val auto = HashMap<Long, ArrayList<Scored>>()
        val uncertain = HashMap<Long, ArrayList<Scored>>()
        for (cands in byPhoto.values) {
            val sorted = cands.sortedByDescending { it.scored.score }
            val used = HashSet<Long>()
            for (c in sorted) {
                if (!c.isAuto || !used.add(c.scored.personId)) continue
                auto.getOrPut(c.scored.personId) { ArrayList() }.add(c.scored)
            }
            for (c in sorted) {
                if (c.isAuto) continue
                if (used.add(c.scored.personId)) uncertain.getOrPut(c.scored.personId) { ArrayList() }.add(c.scored)
            }
        }
        return Picked(auto, uncertain)
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
