package org.fossify.gallery.helpers

import kotlin.math.roundToInt

// Spoločné skóre porovnávača (obrazovka porovnania v mobile, zoznam skupín, porovnávač na PC).
// Čistá logika bez Android tried — merania dodáva CompareAnalysis, testy v CompareScoringTest.
//
// Skóre 0–1 = ostrosť 55 % (relatívne k najostrejšej fotke skupiny) + expozícia 15 % + otvorené oči 25 %
// + úsmev 5 %. Spravodlivosť medzi zábermi:
//  - nenájdená tvár NIE sú otvorené oči: oči sa delia najväčším počtom tvárí v skupine, takže záber,
//    na ktorom sa niekto otočil alebo zakryl, nedostane plné body,
//  - za každú chýbajúcu tvár oproti záberu s najviac tvárami sa strhne časť z MISSING_FACE_PENALTY,
//  - skupina bez tvárí (krajina) sa hodnotí len ostrosťou a expozíciou, prepočítanými na 0–1.
// Pri skoro zhodnom skóre (rozdiel pod TIE_MARGIN) rozhoduje vyššie rozlíšenie, potom priečinok
// (Fotoaparát/DCIM pred ostatnými a tie pred WhatsApp/Download/Screenshots), potom starší súbor —
// pri presných kópiách tak nerozhoduje šum merania, ale originál.

// Surové merania jednej fotky. sharpness je porovnateľná len v rámci skupiny (ImageStats.sharpness),
// facesChecked = tváre sa naozaj hľadali (model bol k dispozícii).
data class PhotoMeasure(
    val path: String,
    val sharpness: Double,
    val exposure: Double,
    val faces: Int = 0,
    val closedEyes: Int = 0,
    val smiles: Int = 0,
    val facesChecked: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val modified: Long = 0L,
) {
    val pixels: Long get() = width.toLong() * height
}

// Zdôvodnenie skóre. value/total podľa druhu: percentá (SHARPER, BLURRIER), počet tvárí (ALL_EYES_OPEN,
// EYES_CLOSED, SMILES), „value z total“ (MORE_FACES, FEWER_FACES), desatiny Mpx (HIGHER/LOWER_RESOLUTION).
// code = stabilný reťazec pre JSON (web porovnávač).
data class Reason(val kind: Kind, val value: Int = 0, val total: Int = 0) {
    enum class Kind(val positive: Boolean) {
        SHARPER(true),
        ALL_EYES_OPEN(true),
        MORE_FACES(true),
        BETTER_EXPOSURE(true),
        SMILES(true),
        HIGHER_RESOLUTION(true),
        ORIGINAL_FOLDER(true),
        OLDER_FILE(true),
        BLURRIER(false),
        EYES_CLOSED(false),
        FEWER_FACES(false),
        WORSE_EXPOSURE(false),
        LOWER_RESOLUTION(false),
    }

    val code: String get() = kind.name.lowercase()
}

// Výsledok pre jednu fotku. rank 0 = najlepšia v skupine (★), reasons: pri ★ prečo vyhrala nad druhou
// najlepšou, pri ostatných čím zaostáva za ★. faces/eyesOpen/smiles sú 0, keď sa tváre nehodnotili.
data class PhotoScore(
    val sharpRel: Double,
    val exposure: Double,
    val faces: Int,
    val eyesOpen: Int,
    val smiles: Int,
    val score: Double,
    val reasons: List<Reason>,
    val rank: Int = 0,
    val facesChecked: Boolean = false,
) {
    val isBest: Boolean get() = rank == 0
    val percent: Int get() = (score * PERCENT).roundToInt()

    private companion object {
        const val PERCENT = 100
    }
}

object CompareScoring {
    const val W_SHARP = 0.55
    const val W_EXPOSURE = 0.15
    const val W_EYES = 0.25
    const val W_SMILE = 0.05
    const val MISSING_FACE_PENALTY = 0.10
    const val TIE_MARGIN = 0.05

    // priečinky pri rozhodovaní medzi skoro rovnakými fotkami (menšie = lepšie)
    const val FOLDER_CAMERA = 0
    const val FOLDER_OTHER = 1
    const val FOLDER_COPY = 2

    private const val SAME_RES = 0.98
    private const val TENTH_MPX = 100_000L
    private const val MAX_REASONS = 3

    private val COPY_FOLDERS = setOf(
        "download", "downloads", "screenshots", "screenshot", "telegram", "telegram images", "messenger",
        "viber", "signal", "instagram", "facebook", "snapchat", "bluetooth", "sent",
    )

    // celá skupina naraz; mapa v poradí vstupu
    fun score(measures: List<PhotoMeasure>): Map<String, PhotoScore> {
        if (measures.isEmpty()) return emptyMap()
        val group = Group.of(measures)
        val scores = measures.map { baseScore(it, group) }
        val order = rankOrder(measures, scores)
        val best = order.first()
        val second = order.getOrNull(1)
        val byIndex = HashMap<Int, PhotoScore>()
        order.forEachIndexed { rank, i ->
            val m = measures[i]
            val reasons = if (rank == 0) {
                ScoreReasons.forBest(m, scores[i], second?.let { measures[it] }, second?.let { scores[it] }, group)
            } else {
                ScoreReasons.forOther(m, measures[best], group)
            }
            val faces = if (group.faceMode) m.faces else 0
            byIndex[i] = PhotoScore(
                sharpRel = group.sharpRel(m),
                exposure = m.exposure,
                faces = faces,
                eyesOpen = if (group.faceMode) (m.faces - m.closedEyes).coerceAtLeast(0) else 0,
                smiles = if (group.faceMode) m.smiles else 0,
                score = scores[i],
                reasons = reasons.take(MAX_REASONS),
                rank = rank,
                facesChecked = group.allChecked,
            )
        }
        val out = LinkedHashMap<String, PhotoScore>()
        measures.forEachIndexed { i, m -> out[m.path] = byIndex.getValue(i) }
        return out
    }

    fun baseScore(m: PhotoMeasure, group: Group): Double {
        val sharp = group.sharpRel(m)
        if (!group.faceMode) {
            return ((W_SHARP * sharp + W_EXPOSURE * m.exposure) / (W_SHARP + W_EXPOSURE)).coerceIn(0.0, 1.0)
        }
        val maxFaces = group.maxFaces.toDouble()
        val open = (m.faces - m.closedEyes).coerceAtLeast(0) / maxFaces
        val smile = m.smiles.coerceAtMost(group.maxFaces) / maxFaces
        val missing = (group.maxFaces - m.faces).coerceAtLeast(0) / maxFaces
        val gain = W_SHARP * sharp + W_EXPOSURE * m.exposure + W_EYES * open + W_SMILE * smile
        return (gain - MISSING_FACE_PENALTY * missing).coerceIn(0.0, 1.0)
    }

    // poradie od najlepšej: z fotiek v pásme TIE_MARGIN pod najvyšším skóre vyberie tú, ktorú uprednostní
    // prefer() (rozlíšenie, priečinok, vek), a opakuje so zvyškom
    fun rankOrder(measures: List<PhotoMeasure>, scores: List<Double>): List<Int> {
        val remaining = measures.indices.toMutableList()
        val order = ArrayList<Int>(measures.size)
        while (remaining.isNotEmpty()) {
            val top = remaining.maxOf { scores[it] }
            var pick = -1
            for (i in remaining) {
                if (scores[i] < top - TIE_MARGIN) continue
                if (pick < 0 || prefer(measures[i], scores[i], measures[pick], scores[pick]) < 0) pick = i
            }
            order.add(pick)
            remaining.remove(pick)
        }
        return order
    }

    // < 0 = a je lepšia voľba než b (len medzi skoro rovnakými fotkami)
    fun prefer(a: PhotoMeasure, scoreA: Double, b: PhotoMeasure, scoreB: Double): Int {
        val res = compareResolution(a, b)
        if (res != 0) return res
        val folder = folderKind(a.path).compareTo(folderKind(b.path))
        if (folder != 0) return folder
        val age = ageKey(a).compareTo(ageKey(b))
        if (age != 0) return age
        val score = scoreB.compareTo(scoreA)
        return if (score != 0) score else a.path.compareTo(b.path)
    }

    // rozlíšenie sa líši zmysluplne (viac ako ~2 %): väčšie vyhráva
    fun compareResolution(a: PhotoMeasure, b: PhotoMeasure): Int {
        val pa = a.pixels
        val pb = b.pixels
        if (pa <= 0L || pb <= 0L) return 0
        return when {
            pa * SAME_RES > pb -> -1
            pb * SAME_RES > pa -> 1
            else -> 0
        }
    }

    // neznámy čas (0) sa nikdy neberie ako „starší“
    private fun ageKey(m: PhotoMeasure): Long = if (m.modified > 0L) m.modified else Long.MAX_VALUE

    fun folderKind(path: String): Int {
        val segments = path.lowercase().substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        return when {
            segments.any { it.startsWith("whatsapp") || it in COPY_FOLDERS } -> FOLDER_COPY
            segments.any { it == "dcim" || it == "camera" } -> FOLDER_CAMERA
            else -> FOLDER_OTHER
        }
    }

    fun tenthsOfMpx(pixels: Long): Int = ((pixels + TENTH_MPX / 2) / TENTH_MPX).toInt()

    // cesty od najlepšej (★ prvá) podľa rank
    fun ordered(scores: Map<String, PhotoScore>): List<String> =
        scores.entries.sortedBy { it.value.rank }.map { it.key }

    // vlastnosti celej skupiny potrebné pre skóre
    class Group(val maxSharp: Double, val maxFaces: Int, val allChecked: Boolean) {
        // tváre sa porovnávajú len vtedy, keď sa hľadali na VŠETKÝCH fotkách a aspoň jedna sa našla
        val faceMode: Boolean get() = allChecked && maxFaces > 0

        fun sharpRel(m: PhotoMeasure): Double = if (maxSharp > 0.0) (m.sharpness / maxSharp).coerceIn(0.0, 1.0) else 0.0

        companion object {
            fun of(measures: List<PhotoMeasure>) = Group(
                maxSharp = measures.maxOfOrNull { it.sharpness } ?: 0.0,
                maxFaces = measures.maxOfOrNull { it.faces } ?: 0,
                allChecked = measures.isNotEmpty() && measures.all { it.facesChecked },
            )
        }
    }
}

// Zdôvodnenia: ★ voči druhej najlepšej, ostatné voči ★ (najviac CompareScoring.MAX_REASONS, od najdôležitejšieho).
internal object ScoreReasons {
    private const val PERCENT = 100.0
    private const val MIN_PCT = 5
    private const val EXPOSURE_GAP = 0.1
    private const val RES_GAP = 0.9

    fun forBest(
        best: PhotoMeasure,
        bestScore: Double,
        second: PhotoMeasure?,
        secondScore: Double?,
        group: CompareScoring.Group,
    ): List<Reason> {
        if (second == null || secondScore == null) return emptyList()
        val out = ArrayList<Reason>()
        if (secondScore >= bestScore - CompareScoring.TIE_MARGIN) tieBreakReason(best, second)?.let { out.add(it) }
        val sharper = percentMore(best.sharpness, second.sharpness)
        if (significant(sharper)) out.add(Reason(Reason.Kind.SHARPER, sharper))
        if (group.faceMode) {
            if (best.faces == group.maxFaces && best.closedEyes == 0) {
                out.add(Reason(Reason.Kind.ALL_EYES_OPEN, best.faces))
            }
            if (best.faces > second.faces) out.add(Reason(Reason.Kind.MORE_FACES, best.faces, second.faces))
            if (best.smiles > second.smiles) out.add(Reason(Reason.Kind.SMILES, best.smiles))
        }
        if (exposureGap(best.exposure, second.exposure)) out.add(Reason(Reason.Kind.BETTER_EXPOSURE))
        return out
    }

    private fun tieBreakReason(best: PhotoMeasure, second: PhotoMeasure): Reason? = when {
        CompareScoring.compareResolution(best, second) < 0 ->
            Reason(Reason.Kind.HIGHER_RESOLUTION, CompareScoring.tenthsOfMpx(best.pixels))
        CompareScoring.folderKind(best.path) < CompareScoring.folderKind(second.path) ->
            Reason(Reason.Kind.ORIGINAL_FOLDER)
        best.modified in 1 until second.modified -> Reason(Reason.Kind.OLDER_FILE)
        else -> null
    }

    fun forOther(m: PhotoMeasure, best: PhotoMeasure, group: CompareScoring.Group): List<Reason> {
        val out = ArrayList<Reason>()
        if (group.faceMode) {
            if (m.closedEyes > 0) out.add(Reason(Reason.Kind.EYES_CLOSED, m.closedEyes))
            if (m.faces < group.maxFaces) out.add(Reason(Reason.Kind.FEWER_FACES, m.faces, group.maxFaces))
        }
        val blurrier = percentLess(m.sharpness, best.sharpness)
        if (significant(blurrier)) out.add(Reason(Reason.Kind.BLURRIER, blurrier))
        if (exposureGap(best.exposure, m.exposure)) out.add(Reason(Reason.Kind.WORSE_EXPOSURE))
        if (clearlyLowerRes(m, best)) {
            out.add(Reason(Reason.Kind.LOWER_RESOLUTION, CompareScoring.tenthsOfMpx(m.pixels)))
        }
        return out
    }

    private fun percentMore(a: Double, b: Double): Int = if (b <= 0.0) 0 else ((a / b - 1.0) * PERCENT).roundToInt()

    private fun percentLess(a: Double, b: Double): Int = if (b <= 0.0) 0 else ((1.0 - a / b) * PERCENT).roundToInt()

    private fun significant(pct: Int) = pct >= MIN_PCT

    private fun exposureGap(a: Double, b: Double) = a - b >= EXPOSURE_GAP

    private fun clearlyLowerRes(a: PhotoMeasure, b: PhotoMeasure) = a.pixels > 0L && a.pixels < b.pixels * RES_GAP
}
