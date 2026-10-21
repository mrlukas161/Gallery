package org.fossify.gallery.faces

import org.fossify.gallery.family.AnchorPicker
import java.util.IdentityHashMap

// C6 — skóre osoby z NAJBLIŽŠÍCH prototypov (k-NN) namiesto jedného priemeru. Jeden priemer zlieva
// rôzne podoby osoby (dieťa v rôznom veku, s okuliarmi a bez, veľká a malá tvár) do bodu „medzi nimi“,
// ku ktorému nie je blízko žiadna skutočná fotka. k-NN porovná tvár s tými potvrdenými tvárami, ktorým
// sa podobá najviac. Rovnaké skóre používa automatika (Ľudia), Návrhy, odhad presnosti (C10) aj
// „Pomôž mi rozhodnúť“ (C11).
//
// Skóre osoby = vážený priemer top-k kosínusov (k = K) k jej prototypom:
//  - prototypy = ručne potvrdené tváre (najviac MAX_MANUAL, výber cez AnchorPicker) + vlastné vzory
//    (Picasa, záloha; najviac MAX_ANCHORS_PER_PERSON, AnchorPicker vyradí odľahlé);
//  - rodinné prototypy (priradenia z prepojenia, vzory „family:“) majú váhu PersonGrouper.familyWeight (C5).
//    Top-k je „rozpočet váhy“ K: najbližšie prototypy sa berú, kým ich súčet váh nedosiahne K. Rodinný
//    vektor s váhou 0,5 je teda pol suseda — jeden chybný vektor z druhého telefónu neurčí skóre sám;
//  - osoba s menším súčtom váh než K použije všetky prototypy, ktoré má;
//  - C8: negatívne prototypy (odmietnuté tváre osoby): skóre −= NEGATIVE_LAMBDA · max(0, neg − pos), kde
//    neg = najbližšia odmietnutá tvár a pos = najbližší vlastný prototyp.
//
// Výkon: kNN pre všetky osoby by bolo 10 000 tvárí × (100 osôb × desiatky prototypov). Preto sa najprv
// spočíta kosínus k ťažisku každej osoby (jeden riadok na osobu) a kNN sa počíta len pre SHORTLIST
// najbližších. Osoba, ktorej ťažisko nie je ani medzi SHORTLIST najbližšími, by nevyhrala ani v kNN.
//
// Model je nemenný (zdieľa sa medzi vláknami). Výpočty robí Ranker — ten má vlastné pracovné polia,
// preto jeden Ranker na vlákno. Čistá logika bez Androidu (testy v KnnModelTest / PersonGrouperTest).
@Suppress("LongParameterList")
class KnnModel private constructor(
    val ids: LongArray,
    private val assignable: BooleanArray,
    private val singlePrototype: BooleanArray,
    internal val centroids: FloatArray,
    internal val protoStart: IntArray,
    internal val protos: FloatArray,
    internal val protoWeight: FloatArray,
    internal val protoFace: LongArray,
    internal val negStart: IntArray,
    internal val negs: FloatArray,
    internal val negFace: LongArray,
    val dim: Int,
) {
    private val indexById = HashMap<Long, Int>().also { m -> ids.forEachIndexed { i, id -> m[id] = i } }

    val size: Int get() = ids.size

    fun indexOf(personId: Long): Int = indexById[personId] ?: -1

    // automaticky sa smie zaradiť len k osobe s vlastným (nie len rodinným) prototypom
    fun isAssignable(index: Int): Boolean = assignable[index]

    fun prototypeCount(index: Int): Int = protoStart[index + 1] - protoStart[index]

    fun negativeCount(index: Int): Int = negStart[index + 1] - negStart[index]

    // Prah automatiky pre osobu. Pri JEDINOM vlastnom prototype je skóre jeden kosínus k jednej fotke —
    // bez priemerovania má rozptyl asi √3-krát väčší ako priemer troch susedov a jedna netypická (alebo
    // chybne potvrdená) fotka by ťahala cudzie tváre. Preto prísnejší prah o SINGLE_PROTOTYPE_EXTRA
    // (≈ rozdiel jednej smerodajnej odchýlky kosínusu tej istej osoby, 0,04–0,06 pri ArcFace-typoch modelov).
    fun autoThreshold(index: Int, base: Float): Float =
        if (singlePrototype[index]) base + SINGLE_PROTOTYPE_EXTRA else base

    fun newRanker(): Ranker = Ranker(this)

    // vstup pre jednu osobu: vektory s id tváre (LOO, C10) alebo bez neho (vzory)
    class PersonInput(
        val personId: Long,
        val ownFaces: List<FaceVec> = emptyList(),
        val ownAnchors: List<FloatArray> = emptyList(),
        val familyFaces: List<FaceVec> = emptyList(),
        val familyAnchors: List<FloatArray> = emptyList(),
        val negatives: List<FaceVec> = emptyList(),
    )

    class FaceVec(val faceId: Long, val vec: FloatArray)

    // Výsledok porovnania tváre so všetkými osobami: najlepšia a druhá najlepšia (bestIndex = index v modeli).
    class Ranking(val bestIndex: Int, val bestId: Long, val best: Float, val secondId: Long, val second: Float) {
        val hasSecond: Boolean get() = secondId != NO_PERSON
        val margin: Float get() = best - second
    }

    // Pracovné polia pre výpočet (nie je thread-safe — jeden na vlákno).
    class Ranker internal constructor(private val m: KnnModel) {
        private val centroidScore = FloatArray(m.size)
        private val shortlist = IntArray(minOf(SHORTLIST, m.size))
        private var sims = FloatArray(INITIAL_BUFFER)
        private var weights = FloatArray(INITIAL_BUFFER)

        // najlepšia a druhá osoba; [banned] = osoby zakázané cannot-linkom, [blocked] = osoby, ktoré na fotke
        // už sú; [exclude] = id tváre, ktorá sa medzi prototypmi aj negatívami nepočíta (leave-one-out)
        fun rank(v: FloatArray, banned: Set<Long>? = null, blocked: Set<Long>? = null, exclude: Long = NO_FACE): Ranking? {
            if (v.size != m.dim) return null
            val n = fillShortlist(v, banned, blocked)
            var bestIdx = -1
            var best = Float.NEGATIVE_INFINITY
            var secondIdx = -1
            var second = Float.NEGATIVE_INFINITY
            for (s in 0 until n) {
                val p = shortlist[s]
                val score = score(v, p, exclude)
                if (score == Float.NEGATIVE_INFINITY) continue
                if (score > best) {
                    secondIdx = bestIdx
                    second = best
                    bestIdx = p
                    best = score
                } else if (score > second) {
                    secondIdx = p
                    second = score
                }
            }
            if (bestIdx < 0) return null
            val secondId = if (secondIdx >= 0) m.ids[secondIdx] else NO_PERSON
            return Ranking(bestIdx, m.ids[bestIdx], best, secondId, second)
        }

        // SHORTLIST osôb s najvyšším kosínusom k ťažisku (bez zakázaných); vráti ich počet
        private fun fillShortlist(v: FloatArray, banned: Set<Long>?, blocked: Set<Long>?): Int {
            var n = 0
            for (p in 0 until m.size) {
                val pid = m.ids[p]
                if (banned?.contains(pid) == true || blocked?.contains(pid) == true) continue
                val s = dot(v, m.centroids, p * m.dim, m.dim)
                centroidScore[p] = s
                if (n < shortlist.size) {
                    shortlist[n++] = p
                    siftUp(n - 1)
                } else if (s > centroidScore[shortlist[n - 1]]) {
                    shortlist[n - 1] = p
                    siftUp(n - 1)
                }
            }
            return n
        }

        // zoznam je zoradený zostupne podľa skóre ťažiska — vlož posledný prvok na správne miesto
        private fun siftUp(from: Int) {
            var i = from
            while (i > 0 && centroidScore[shortlist[i]] > centroidScore[shortlist[i - 1]]) {
                val t = shortlist[i]
                shortlist[i] = shortlist[i - 1]
                shortlist[i - 1] = t
                i--
            }
        }

        // kNN skóre tváre [v] pre osobu s indexom [p] (vrátane penalizácie za odmietnuté tváre)
        fun score(v: FloatArray, p: Int, exclude: Long = NO_FACE): Float {
            if (v.size != m.dim) return Float.NEGATIVE_INFINITY
            val from = m.protoStart[p]
            val n = m.protoStart[p + 1] - from
            if (n == 0) return Float.NEGATIVE_INFINITY
            ensure(n)
            var maxPos = Float.NEGATIVE_INFINITY
            for (j in 0 until n) {
                val g = from + j
                if (m.protoFace[g] == exclude && exclude != NO_FACE) {
                    sims[j] = Float.NEGATIVE_INFINITY
                    weights[j] = 0f
                    continue
                }
                val s = dot(v, m.protos, g * m.dim, m.dim)
                sims[j] = s
                weights[j] = m.protoWeight[g]
                if (s > maxPos) maxPos = s
            }
            val knn = weightedTopK(n)
            if (knn == Float.NEGATIVE_INFINITY) return knn
            return knn - negativePenalty(v, p, maxPos, exclude)
        }

        // vážený priemer najbližších prototypov, kým súčet váh nedosiahne K (alebo sa neminú)
        private fun weightedTopK(n: Int): Float {
            var acc = 0f
            var used = 0f
            var picks = 0
            while (used < K - EPS && picks < MAX_PICKS) {
                var bi = -1
                var bs = Float.NEGATIVE_INFINITY
                for (j in 0 until n) {
                    if (weights[j] > 0f && sims[j] > bs) {
                        bs = sims[j]
                        bi = j
                    }
                }
                if (bi < 0) break
                val w = minOf(weights[bi], K - used)
                acc += w * bs
                used += w
                weights[bi] = 0f
                picks++
            }
            return if (used <= 0f) Float.NEGATIVE_INFINITY else acc / used
        }

        private fun negativePenalty(v: FloatArray, p: Int, maxPos: Float, exclude: Long): Float {
            val from = m.negStart[p]
            val to = m.negStart[p + 1]
            if (from == to) return 0f
            var maxNeg = Float.NEGATIVE_INFINITY
            for (g in from until to) {
                if (exclude != NO_FACE && m.negFace[g] == exclude) continue
                val s = dot(v, m.negs, g * m.dim, m.dim)
                if (s > maxNeg) maxNeg = s
            }
            if (maxNeg == Float.NEGATIVE_INFINITY) return 0f
            return NEGATIVE_LAMBDA * maxOf(0f, maxNeg - maxPos)
        }

        private fun ensure(n: Int) {
            if (sims.size >= n) return
            sims = FloatArray(n)
            weights = FloatArray(n)
        }
    }

    companion object {
        // počet susedov: 1 = citlivé na jednu chybnú/netypickú fotku, 5+ = pri osobe s niekoľkými podobami
        // (dieťa v rôznom veku) by sa do priemeru miešali susedia z inej podoby
        const val K = 3f

        // najviac ručne potvrdených tvárí ako prototypov na osobu (výber cez AnchorPicker — rôzne pózy a roky)
        const val MAX_MANUAL = 200

        // najviac vlastných vzorov (Picasa, záloha) na osobu
        const val MAX_ANCHORS_PER_PERSON = 50

        // najviac rodinných prototypov (priradenia z prepojenia + vzory „family:“) na osobu
        const val MAX_FAMILY = 200

        // najviac odmietnutých tvárí na osobu — berú sa tie najbližšie osobe (najviac mätúce)
        const val MAX_NEGATIVES = 200

        // C8 — sila penalizácie za podobnosť s odmietnutou tvárou. Tvár, ktorá je odmietnutej tvári rovnako
        // blízko ako najbližšej potvrdenej, nestratí nič; za každých 0,1, o ktoré je bližšie odmietnutej,
        // stratí 0,05. To je v rádovo rovnakom rozsahu ako rozostup AUTO_MARGIN (0,07): takmer kópia
        // odmietnutej tváre (napr. ďalší záber zo série) sa už automaticky nezaradí, ale vzdialená
        // odmietnutá tvár (iný človek v inom kúte priestoru) skóre neovplyvní.
        const val NEGATIVE_LAMBDA = 0.5f

        // prísnejší prah pri jedinom vlastnom prototype (zdôvodnenie pri autoThreshold)
        const val SINGLE_PROTOTYPE_EXTRA = 0.05f

        // koľko osôb s najbližším ťažiskom dostane presné kNN skóre
        const val SHORTLIST = 8

        const val NO_FACE = Long.MIN_VALUE
        const val NO_PERSON = Long.MIN_VALUE

        private const val EPS = 1e-4f

        // najviac výberov do rozpočtu K (pri veľmi malých rodinných váhach)
        private const val MAX_PICKS = 24
        private const val INITIAL_BUFFER = 64
        private const val UNROLL = 4

        fun build(inputs: List<PersonInput>): KnnModel? {
            val dim = inputs.firstNotNullOfOrNull { firstDim(it) } ?: return null
            val acc = Accumulator(dim)
            for (input in inputs) acc.add(input)
            return acc.build()
        }

        private fun firstDim(p: PersonInput): Int? =
            (p.ownFaces.map { it.vec } + p.ownAnchors + p.familyFaces.map { it.vec } + p.familyAnchors)
                .firstOrNull { it.isNotEmpty() }?.size

        // kosínus medzi L2-normalizovanými vektormi; štyri nezávislé súčty (JIT/ART ich počíta súbežne)
        internal fun dot(v: FloatArray, flat: FloatArray, off: Int, dim: Int): Float {
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

        // výber najviac [max] vektorov cez AnchorPicker so zachovaním id tváre (AnchorPicker vracia tie isté polia)
        internal fun pickFaces(faces: List<FaceVec>, max: Int): List<FaceVec> {
            if (faces.size <= max) return faces
            val byVec = IdentityHashMap<FloatArray, FaceVec>()
            faces.forEach { byVec[it.vec] = it }
            return AnchorPicker.pick(faces.map { it.vec }, max).mapNotNull { byVec[it] }
        }
    }

    // skladanie súvislých polí modelu
    private class Accumulator(val dim: Int) {
        val ids = ArrayList<Long>()
        val assignable = ArrayList<Boolean>()
        val single = ArrayList<Boolean>()
        val centroids = ArrayList<FloatArray>()
        val protoStart = arrayListOf(0)
        val protos = ArrayList<FloatArray>()
        val weights = ArrayList<Float>()
        val protoFaces = ArrayList<Long>()
        val negStart = arrayListOf(0)
        val negs = ArrayList<FloatArray>()
        val negFaces = ArrayList<Long>()

        fun add(p: PersonInput) {
            val own = pickFaces(p.ownFaces.filter { it.vec.size == dim }, MAX_MANUAL)
            val ownAnchors = AnchorPicker.pick(p.ownAnchors.filter { it.size == dim }, MAX_ANCHORS_PER_PERSON)
            val family = pickFaces(
                p.familyFaces.filter { it.vec.size == dim } +
                    p.familyAnchors.filter { it.size == dim }.map { FaceVec(NO_FACE, it) },
                MAX_FAMILY,
            )
            val ownCount = own.size + ownAnchors.size
            if (ownCount + family.size == 0) return
            val fw = PersonGrouper.familyWeight(ownCount, family.size)
            val ownVecs = own.map { it.vec } + ownAnchors
            val centroid = PersonGrouper.weightedCentroid(ownVecs, family.map { it.vec }, fw)
                ?: PersonGrouper.centroidOf(family.map { it.vec })
                ?: return
            ids.add(p.personId)
            assignable.add(ownCount > 0)
            single.add(ownCount == 1)
            centroids.add(centroid)
            own.forEach { addProto(it.vec, 1f, it.faceId) }
            ownAnchors.forEach { addProto(it, 1f, NO_FACE) }
            // rodinné len s kladnou váhou (familyWeight je 0, ak osoba nemá vlastné — vtedy plná FAMILY_WEIGHT,
            // aby osoba len z prepojenia mala návrhy aj súpera v rozostupe; automaticky sa jej nezaradí nič)
            val famW = if (ownCount == 0) PersonGrouper.FAMILY_WEIGHT else fw
            if (famW > 0f) family.forEach { addProto(it.vec, famW, it.faceId) }
            protoStart.add(protos.size)
            p.negatives.filter { it.vec.size == dim }
                .sortedByDescending { PersonGrouper.cosine(it.vec, centroid) }
                .take(MAX_NEGATIVES)
                .forEach {
                    negs.add(it.vec)
                    negFaces.add(it.faceId)
                }
            negStart.add(negs.size)
        }

        private fun addProto(v: FloatArray, w: Float, faceId: Long) {
            protos.add(v)
            weights.add(w)
            protoFaces.add(faceId)
        }

        fun build(): KnnModel? {
            if (ids.isEmpty()) return null
            return KnnModel(
                ids.toLongArray(), assignable.toBooleanArray(), single.toBooleanArray(),
                flatten(centroids), protoStart.toIntArray(), flatten(protos), weights.toFloatArray(),
                protoFaces.toLongArray(), negStart.toIntArray(), flatten(negs), negFaces.toLongArray(), dim,
            )
        }

        private fun flatten(list: List<FloatArray>): FloatArray {
            val flat = FloatArray(list.size * dim)
            list.forEachIndexed { i, v -> v.copyInto(flat, i * dim) }
            return flat
        }
    }
}
