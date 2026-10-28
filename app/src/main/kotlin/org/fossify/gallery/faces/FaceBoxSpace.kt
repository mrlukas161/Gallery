package org.fossify.gallery.faces

import org.fossify.gallery.family.RelBox

// E1 — v akom priestore je uložený rámček tváre (faces.box_space). Čistá logika bez Androidu
// (FaceBoxSpaceTest).
//  - UNKNOWN (0): neznámy — staré riadky pred faces.db v3; na fotke s EXIF otočením môže byť rámček
//    v surovom (neotočenom) priestore (pred 0.47) alebo v upright v1. Pre tieto ostáva hádanie dvoch hypotéz.
//  - UPRIGHT_V1 (1): upright bitmapa z UprightDecoder pred opravou EXIF 5/7 — pri orientácii 5 (TRANSPOSE)
//    a 7 (TRANSVERSE) bola otočená o 180° oproti Glide (zobrazeniu). Pri ostatných orientáciách je zhodná s v2.
//  - UPRIGHT_V2 (2): upright bitmapa z opraveného UprightDecoder = presne to, čo ukazuje Glide.
// Nové tváre (FaceIndexer, RefreshFaces, balíky z prepojenia) sa ukladajú vždy ako UPRIGHT_V2. Staré tváre
// preklopí FaceMetaBackfill: v1 na fotke 5/7 otočí o 180° a nastaví v2; tam, kde sa priestor zistiť nedá,
// nechá UNKNOWN (spätná kompatibilita: dve hypotézy ako doteraz).
object FaceBoxSpace {
    const val UNKNOWN = 0
    const val UPRIGHT_V1 = 1
    const val UPRIGHT_V2 = 2

    private const val LEFT = 0
    private const val TOP = 1
    private const val RIGHT = 2
    private const val BOTTOM = 3

    // je priestor rámčeka istý (upright v1 alebo v2)?
    fun isKnown(space: Int): Boolean = space == UPRIGHT_V1 || space == UPRIGHT_V2

    // EXIF 5/7 = zrkadlenie + otočenie o 90/270 (po oprave TRANSPOSE = zrkadlenie + 270°, TRANSVERSE = + 90°)
    fun isTransposed(rotationDegrees: Int, mirrored: Boolean): Boolean {
        val r = Math.floorMod(rotationDegrees, FULL_TURN)
        return mirrored && (r == RelBox.DEG_90 || r == RelBox.DEG_270)
    }

    fun isTransposed(transform: RelBox.Transform): Boolean =
        isTransposed(transform.rotationDegrees, transform.mirrored)

    fun isTransposedExif(orientation: Int): Boolean =
        orientation == RelBox.EXIF_TRANSPOSE || orientation == RelBox.EXIF_TRANSVERSE

    // Rámček so známym priestorom prevedený do upright v2 (obrázok width × height je upright, rovnaký pre v1
    // aj v2, lebo 5 aj 7 vymieňajú šírku a výšku). v1 na fotke 5/7 = otočenie o 180°, inak bez zmeny.
    fun toV2(box: IntArray, space: Int, transposed: Boolean, width: Int, height: Int): IntArray =
        if (space == UPRIGHT_V1 && transposed) rotate180(box, width, height) else box

    fun rotate180(box: IntArray, width: Int, height: Int): IntArray = intArrayOf(
        width - box[RIGHT], height - box[BOTTOM], width - box[LEFT], height - box[TOP],
    )

    // Výsledok odvodenia priestoru pre starú tvár (FaceMetaBackfill): nový box_space a či treba rámček
    // otočiť o 180° (v1 → v2 na fotke 5/7).
    data class Derived(val space: Int, val rotate180: Boolean)

    // Priestor starej tváre (box_space = UNKNOWN). [transform] = EXIF transformácia fotky (RelBox.exifTransform,
    // už opravená), [uprightV1] = je rámček v upright priestore starého dekodéra (true), v surovom (false),
    // alebo sa to nevie (null) — zisťuje volajúci z dátumu detekcie (0.47), geometrie a overenia odtlačkom (D3).
    // null = nechať UNKNOWN.
    fun derive(transform: RelBox.Transform, uprightV1: Boolean?): Derived? = when {
        // bez otočenia a zrkadlenia sú surový aj oba upright priestory totožné
        transform.rotationDegrees % FULL_TURN == 0 && !transform.mirrored -> Derived(UPRIGHT_V2, false)
        uprightV1 != true -> null
        isTransposed(transform) -> Derived(UPRIGHT_V2, true)
        else -> Derived(UPRIGHT_V2, false)
    }

    private const val FULL_TURN = 360
}
