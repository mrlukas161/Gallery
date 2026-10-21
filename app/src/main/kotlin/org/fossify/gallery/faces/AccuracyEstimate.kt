package org.fossify.gallery.faces

import kotlin.math.roundToInt

// C10 — odhad presnosti zaraďovania metódou leave-one-out na ručne potvrdených tvárach: každá potvrdená
// tvár sa ohodnotí kNN modelom (KnnModel), z ktorého je dočasne vyňatá (nie je medzi prototypmi ani
// medzi odmietnutými tvárami), a porovná sa, či by ju automatika zaradila správne. Zisťuje sa:
//  - presnosť automatiky pri aktuálnom prahu a podiel tvárí, ktoré by skončili ako „neisté“;
//  - pre každú osobu najviac zameniteľná iná osoba (tvár išla k nej, alebo bola rozostup malý);
//  - odporúčaný prah: najnižší prah, od ktorého je presnosť pri každom vyššom prahu aspoň TARGET_PRECISION
//    (len ako text v Nastaveniach, prah sa nemení sám).
// Zjednodušenia (vedome): ťažisko na predvýber osôb (KnnModel.SHORTLIST) vyňatú tvár ešte obsahuje
// (jedna tvár z desiatok ho posunie zanedbateľne) a pravidlo „raz na fotke“ sa tu neuplatňuje.
// Čistá logika bez Androidu (AccuracyEstimateTest), výsledok sa ukladá ako text (encode/decode).
object AccuracyEstimate {
    // cieľová presnosť pre odporúčaný prah (2 chyby zo 100 automatických zaradení)
    const val TARGET_PRECISION = 0.98f

    // odporúčanie len z dostatočného počtu automatických zaradení (inak je presnosť náhoda)
    const val MIN_AUTO_FOR_RECOMMENDATION = 20

    // dvojica sa „pletie“, keď sa zamenilo aspoň toľko tvárí a aspoň taký podiel tvárí osoby
    const val MIN_CONFUSED = 3
    const val MIN_CONFUSED_SHARE = 0.1f

    // najviac toľko vyhodnotených tvárí (rovnomerne zo všetkých osôb) — výpočet ostane do pár sekúnd
    const val MAX_SAMPLES = 2000

    private const val SCAN_FROM = 0.50f
    private const val SCAN_TO = 0.95f
    private const val SCAN_STEP = 0.01f
    private const val FORMAT = "1"
    private const val FIELDS = 9
    private const val PERCENT = 100

    class Sample(val faceId: Long, val personId: Long, val vec: FloatArray)

    // osoba [personId] sa najčastejšie plietla s [otherId]: [count] z jej [samples] vyhodnotených tvárí
    data class Confusion(val personId: Long, val otherId: Long, val count: Int, val samples: Int)

    data class Result(
        val evaluated: Int,
        val autoCount: Int,
        val autoCorrect: Int,
        val uncertainCount: Int,
        val threshold: Float,
        val recommended: Float?,
        val confusions: List<Confusion>,
        val computedAt: Long,
    ) {
        val precision: Float? get() = if (autoCount > 0) autoCorrect.toFloat() / autoCount else null
        val uncertainShare: Float get() = if (evaluated > 0) uncertainCount.toFloat() / evaluated else 0f

        // dvojica, ktorá sa pletie najviac (pre upozornenie v Ľuďoch), alebo null
        fun worstPair(): Confusion? = confusions
            .filter { it.count >= MIN_CONFUSED && it.count >= it.samples * MIN_CONFUSED_SHARE }
            .maxByOrNull { it.count }
    }

    // jedna vyhodnotená tvár: skóre najlepšej osoby, či ide o správnu a či by prešla rozostupom
    private class Row(val best: Float, val index: Int, val correct: Boolean, val marginOk: Boolean)

    fun run(
        model: KnnModel,
        samples: List<Sample>,
        threshold: Float,
        margin: Float = PersonGrouper.AUTO_MARGIN,
        now: Long = 0L,
    ): Result {
        val th = if (threshold > 0f) threshold else PersonGrouper.REFERENCE_THRESHOLD
        val tally = Tally(model, th, margin)
        val ranker = model.newRanker()
        for (s in samples) tally.add(s, ranker.rank(s.vec, exclude = s.faceId))
        return Result(
            samples.size, tally.auto, tally.correct, tally.uncertain, th,
            recommend(model, tally.rows), tally.confusions(), now,
        )
    }

    // súčty leave-one-out pri prahu [th]
    private class Tally(val model: KnnModel, val th: Float, val margin: Float) {
        private val low = PersonGrouper.uncertainFloor(th)
        val rows = ArrayList<Row>()
        private val confused = HashMap<Long, HashMap<Long, Int>>()
        private val perPerson = HashMap<Long, Int>()
        var auto = 0
        var correct = 0
        var uncertain = 0

        fun add(s: Sample, r: KnnModel.Ranking?) {
            perPerson[s.personId] = (perPerson[s.personId] ?: 0) + 1
            if (r == null) return
            val row = Row(r.best, r.bestIndex, r.bestId == s.personId, r.margin >= margin)
            rows.add(row)
            if (isAuto(model, row, th)) {
                auto++
                if (row.correct) correct++
            } else if (r.best >= low) {
                uncertain++
            }
            confusedWith(s, r)?.let { other ->
                val m = confused.getOrPut(s.personId) { HashMap() }
                m[other] = (m[other] ?: 0) + 1
            }
        }

        // iná osoba, s ktorou sa tvár zamenila (vyhrala) alebo ktorá bola bližšie než rozostup
        private fun confusedWith(s: Sample, r: KnnModel.Ranking): Long? = when {
            r.bestId != s.personId -> r.bestId
            r.hasSecond && r.margin < margin && r.second >= low -> r.secondId
            else -> null
        }

        fun confusions(): List<Confusion> = confused.map { (pid, others) ->
            val top = others.entries.maxWith(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { -it.key })
            Confusion(pid, top.key, top.value, perPerson[pid] ?: 0)
        }.sortedByDescending { it.count }
    }

    private fun isAuto(model: KnnModel, row: Row, th: Float) =
        row.marginOk && model.isAssignable(row.index) && row.best >= model.autoThreshold(row.index, th)

    // od najvyššieho prahu nadol, kým presnosť drží cieľ; vráti posledný prah, ktorý ešte prešiel
    private fun recommend(model: KnnModel, rows: List<Row>): Float? {
        var result: Float? = null
        var steps = ((SCAN_TO - SCAN_FROM) / SCAN_STEP).toInt()
        while (steps >= 0) {
            val t = SCAN_FROM + steps * SCAN_STEP
            var n = 0
            var ok = 0
            for (row in rows) {
                if (!isAuto(model, row, t)) continue
                n++
                if (row.correct) ok++
            }
            if (n >= MIN_AUTO_FOR_RECOMMENDATION) {
                if (ok.toFloat() / n < TARGET_PRECISION) break
                result = (t * PERCENT).roundToInt() / PERCENT.toFloat()
            }
            steps--
        }
        return result
    }

    // najviac [max] tvárí, z každej osoby pomerne (rovnomerne v poradí, deterministicky)
    fun sample(all: List<Sample>, max: Int = MAX_SAMPLES): List<Sample> {
        if (all.size <= max) return all
        val step = all.size.toDouble() / max
        return List(max) { all[(it * step).toInt()] }
    }

    fun encode(r: Result): String {
        val confs = r.confusions.joinToString(",") { "${it.personId}:${it.otherId}:${it.count}:${it.samples}" }
        return listOf(
            FORMAT, r.evaluated, r.autoCount, r.autoCorrect, r.uncertainCount, r.threshold,
            r.recommended ?: -1f, r.computedAt, confs,
        ).joinToString(";")
    }

    // poškodený alebo starý formát = žiadny odhad (prepočíta sa)
    fun decode(s: String?): Result? {
        val parts = s?.split(";") ?: return null
        if (parts.size != FIELDS || parts[0] != FORMAT) return null
        return try {
            val it = parts.iterator().also { i -> i.next() }
            val evaluated = it.next().toInt()
            val auto = it.next().toInt()
            val correct = it.next().toInt()
            val uncertain = it.next().toInt()
            val threshold = it.next().toFloat()
            val recommended = it.next().toFloat().takeIf { r -> r >= 0f }
            val computedAt = it.next().toLong()
            val confs = it.next()
            Result(evaluated, auto, correct, uncertain, threshold, recommended, decodeConfusions(confs), computedAt)
        } catch (ignored: NumberFormatException) {
            null
        } catch (ignored: NoSuchElementException) {
            null
        }
    }

    // „osoba:iná:počet:vzorky“ oddelené čiarkou
    private fun decodeConfusions(s: String): List<Confusion> = if (s.isEmpty()) {
        emptyList()
    } else {
        s.split(",").map { c ->
            val f = c.split(":").iterator()
            Confusion(f.next().toLong(), f.next().toLong(), f.next().toInt(), f.next().toInt())
        }
    }
}
