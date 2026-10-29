package org.fossify.gallery.faces

import org.fossify.gallery.family.RelBox
import kotlin.math.abs

// A6: prepočet rámčekov tvárí, keď používateľ otočí fotku v appke (prehliadač, výber v mriežke,
// zoznam fotiek osoby). Rámčeky sú uložené v upright priestore bitmapy z UprightDecoder.decode(path, 1024),
// takže otočenie fotky o 90/180/270° v smere hodín je len otočenie tohto obdĺžnika — id tvárí,
// menovky aj odtlačky (počítané z výrezu, ktorý sa otočením nemení) ostávajú platné.
// Čistá logika bez Androidu (testovaná vo FaceBoxRotationTest).
object FaceBoxRotation {
    // Od tohto času sú všetky nové rámčeky v upright priestore (= BundleRules.UPRIGHT_SINCE_MS,
    // FaceIndexer od 0.47.0). Staršie na fotke s EXIF otočením môžu byť v surovom (neotočenom)
    // priestore a DB to nerozlišuje — také sa otáčať nesmú.
    const val UPRIGHT_SINCE_MS = 1_788_134_400_000L // 31.8.2026 00:00 UTC

    private const val FULL_TURN = 360

    // tolerancia rozmerov po otočení (dekodér môže pri nedeliteľných rozmeroch zaokrúhliť o 1 px)
    private const val SIZE_TOLERANCE = 1

    sealed interface Decision {
        // nič netreba (otočenie o 0° alebo fotka bez tvárí)
        data object Unchanged : Decision

        // otočiť všetky rámčeky o `degrees` v smere hodín v obrázku width × height (rozmery PRED otočením)
        data class Rotate(val degrees: Int, val width: Int, val height: Int) : Decision

        // rámčeky sa nedajú bezpečne otočiť → prepracovať fotku (RefreshFaces, menovky cez IoU)
        data object Refresh : Decision
    }

    // Stav fotky pred alebo po otočení: EXIF orientácia a rozmery upright bitmapy (UprightDecoder.uprightSize).
    data class State(val exifOrientation: Int, val width: Int, val height: Int)

    // Rozhodne, čo spraviť s rámčekmi fotky po otočení o `degrees` (v smere hodín, môže byť záporné).
    //  - pixelsRewritten = false: zmenil sa len EXIF tag (JPEG), pixely v súbore ostali,
    //  - pixelsRewritten = true: pixely sa otočili a zapísali nanovo (PNG/WebP…), EXIF sa nezachová.
    // faceDetectedAt = detected_at všetkých tvárí fotky. after = skutočný stav súboru po otočení
    // (overenie, že výsledok je naozaj čisté otočenie; null = nečitateľný → prepracovať).
    // E1: faceBoxSpaces = box_space tých istých tvárí (v rovnakom poradí); tvár so známym priestorom je upright
    // aj pri starom detected_at.
    fun decide(
        before: State?,
        after: State?,
        degrees: Int,
        pixelsRewritten: Boolean,
        faceDetectedAt: List<Long>,
        faceBoxSpaces: List<Int> = emptyList(),
    ): Decision {
        if (faceDetectedAt.isEmpty()) return Decision.Unchanged
        if (before == null || after == null) return Decision.Refresh
        val turn = pureTurn(before, after, degrees, pixelsRewritten)
        return when {
            turn == null -> Decision.Refresh
            turn == 0 -> Decision.Unchanged
            // staré rámčeky (pred 0.47) na fotke s EXIF otočením môžu byť v surovom priestore
            !isNormal(before.exifOrientation) && mayBeRaw(faceDetectedAt, faceBoxSpaces) -> Decision.Refresh
            !sizeMatches(before, after, turn) -> Decision.Refresh
            else -> Decision.Rotate(turn, before.width, before.height)
        }
    }

    // Skutočné otočenie upright obrázka (0/90/180/270), alebo null, keď výsledok nie je čisté otočenie
    // o `degrees` (napr. dvojité otočenie po čiastočne úspešnom pokuse, zrkadlenie, nečitateľné rozmery).
    private fun pureTurn(before: State, after: State, degrees: Int, pixelsRewritten: Boolean): Int? {
        val wanted = normalize(degrees)
        val beforeT = RelBox.exifTransform(before.exifOrientation)
        val afterT = RelBox.exifTransform(after.exifOrientation)
        val actual = when {
            before.width <= 0 || before.height <= 0 || wanted % RelBox.DEG_90 != 0 -> null
            // zrkadlené orientácie: otočenie v commons zrkadlenie zahodí, nejde o čisté otočenie
            beforeT.mirrored || afterT.mirrored -> null
            // pixely sa otočili zo SUROVÉHO obrázka (BitmapFactory ignoruje EXIF) — čisté otočenie
            // upright obrázka je to len pri fotke bez EXIF otočenia pred aj po
            pixelsRewritten -> wanted.takeIf { beforeT.rotationDegrees == 0 && afterT.rotationDegrees == 0 }
            else -> normalize(afterT.rotationDegrees - beforeT.rotationDegrees)
        }
        return actual?.takeIf { it == wanted }
    }

    private fun mayBeRaw(detectedAt: List<Long>, spaces: List<Int>): Boolean =
        detectedAt.indices.any { i ->
            detectedAt[i] < UPRIGHT_SINCE_MS && !FaceBoxSpace.isKnown(spaces.getOrElse(i) { FaceBoxSpace.UNKNOWN })
        }

    private fun sizeMatches(before: State, after: State, turn: Int): Boolean {
        val expected = rotatedSize(before.width, before.height, turn)
        return abs(expected[0] - after.width) <= SIZE_TOLERANCE && abs(expected[1] - after.height) <= SIZE_TOLERANCE
    }

    // Otočenie rámčeka [left, top, right, bottom] v obrázku width × height o degrees v smere hodín
    // (rovnako ako UprightDecoder.mapBox bez zrkadlenia). Pri 90° je to (h-b, l, h-t, r).
    fun rotate(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int, degrees: Int): IntArray =
        when (normalize(degrees)) {
            RelBox.DEG_90 -> intArrayOf(height - bottom, left, height - top, right)
            RelBox.DEG_180 -> intArrayOf(width - right, height - bottom, width - left, height - top)
            RelBox.DEG_270 -> intArrayOf(top, width - right, bottom, width - left)
            else -> intArrayOf(left, top, right, bottom)
        }

    // rozmery obrázka po otočení (90/270 vymení šírku a výšku)
    fun rotatedSize(width: Int, height: Int, degrees: Int): IntArray =
        RelBox.uprightSize(width, height, normalize(degrees))

    // EXIF bez otočenia (0 = nedefinované, 1 = normálne)
    fun isNormal(exifOrientation: Int): Boolean =
        exifOrientation == RelBox.EXIF_NORMAL || exifOrientation == RelBox.EXIF_UNDEFINED

    // Dovolené otočenia sú len násobky 90°; iné (nemalo by nastať) sa berú ako neznáme.
    fun normalize(degrees: Int): Int = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
}
