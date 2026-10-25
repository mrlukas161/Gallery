package org.fossify.gallery.family

import org.fossify.gallery.faces.FaceBoxSpace

// Spárovanie tváre z druhého telefónu (FaceRef) s lokálnou tvárou na tej istej fotke.
// Čistá logika bez Androidu (testovaná vo FaceMatchTest).
//
// Rámčeky vo faces.db sú v priestore bitmapy z UprightDecoder.decode(path, 1024). Staré záznamy
// (pred 0.47) na fotkách s EXIF otočením môžu byť v neotočenom priestore; od faces.db v3 to rozlišuje
// box_space (E1, FaceBoxSpace). Len pri neznámom priestore a otočenej fotke vzniknú DVE hypotézy rámčeka
// (upright aj surová) a tvár sa spáruje len vtedy, keď zhoda vyjde jednoznačne.
object FaceMatch {
    // rovnaký limit dekódovania ako FaceIndexer/RefreshFaces/FaceCropLoader (MAX_DECODE_SIZE)
    const val DECODE_MAX_SIZE = 1024

    // minimálny prekryv (IoU) relatívnych rámčekov, aby išlo o tú istú tvár
    const val MIN_IOU = 0.5f

    // D2: presná kópia (rovnaký obsahový kľúč) má rovnaké pixely aj detekciu, rámčeky sa musia prekrývať viac
    const val MIN_IOU_EXACT = 0.7f

    // keď sa dve lokálne tváre zhodujú takmer rovnako (duplicitné riadky), radšej nič
    const val AMBIGUITY_MARGIN = 0.05f

    // Relatívne rámčeky uloženého rámčeka (FaceEntity.bbox*) fotky rawWidth × rawHeight (hlavička súboru)
    // s EXIF transformáciou. Prvý je upright hypotéza; pri otočení/zrkadlení a neznámom priestore
    // (boxSpace = FaceBoxSpace.UNKNOWN) pribudne surová. Pri známom priestore (E1) je hypotéza jediná.
    // Upright v1 na fotke s EXIF 5/7 je o 180° otočený oproti v2 (oprava UprightDecoder) — prevedie sa na v2;
    // neznámy priestor na fotke 5/7 je upright v1 alebo surový (v2 rámčeky majú vždy box_space = 2).
    @Suppress("LongParameterList")
    fun relBoxes(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rawWidth: Int,
        rawHeight: Int,
        transform: RelBox.Transform,
        boxSpace: Int = FaceBoxSpace.UNKNOWN,
    ): List<RelBox> {
        if (rawWidth <= 0 || rawHeight <= 0) return emptyList()
        val upright = RelBox.decodedUprightSize(rawWidth, rawHeight, DECODE_MAX_SIZE, transform.rotationDegrees)
        val v2 = if (boxSpace == FaceBoxSpace.UPRIGHT_V2) {
            intArrayOf(left, top, right, bottom)
        } else {
            FaceBoxSpace.toV2(
                intArrayOf(left, top, right, bottom), FaceBoxSpace.UPRIGHT_V1, FaceBoxSpace.isTransposed(transform),
                upright[0], upright[1],
            )
        }
        val asUpright = RelBox.fromAbsolute(v2[0], v2[1], v2[2], v2[v2.size - 1], upright[0], upright[1])
        if (FaceBoxSpace.isKnown(boxSpace)) return listOf(asUpright)
        if (transform.rotationDegrees == 0 && !transform.mirrored) return listOf(asUpright)
        val raw = RelBox.decodedUprightSize(rawWidth, rawHeight, DECODE_MAX_SIZE, 0)
        val asRaw = RelBox.fromAbsolute(left, top, right, bottom, raw[0], raw[1])
            .transformed(transform.rotationDegrees, transform.mirrored)
        return listOf(asUpright, asRaw)
    }

    fun iou(a: RelBox, b: RelBox): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = area(a) + area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }

    // najlepší prekryv medzi ľubovoľnou hypotézou jednej a druhej strany
    fun score(remote: List<RelBox>, local: List<RelBox>): Float {
        var best = 0f
        for (r in remote) for (l in local) best = maxOf(best, iou(r, l))
        return best
    }

    // Id lokálnej tváre, ktorá zodpovedá vzdialenej, alebo null (žiadna alebo nejednoznačná).
    fun <T> best(remote: List<RelBox>, candidates: List<Pair<T, List<RelBox>>>, minIou: Float = MIN_IOU): T? {
        var bestId: T? = null
        var bestScore = 0f
        var secondScore = 0f
        for ((id, boxes) in candidates) {
            val s = score(remote, boxes)
            if (s > bestScore) {
                secondScore = bestScore
                bestScore = s
                bestId = id
            } else if (s > secondScore) {
                secondScore = s
            }
        }
        val rival = secondScore >= minOf(minIou, MIN_IOU) && bestScore - secondScore < AMBIGUITY_MARGIN
        val clear = bestScore >= minIou && !rival
        return if (clear) bestId else null
    }

    private fun area(b: RelBox): Float = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)
}
