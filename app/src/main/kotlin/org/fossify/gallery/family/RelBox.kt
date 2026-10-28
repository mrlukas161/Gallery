package org.fossify.gallery.family

import kotlin.math.roundToInt

// Rámček tváre v RELATÍVNYCH súradniciach 0–1 voči VZPRIAMENÉMU obrázku (po EXIF orientácii,
// rovnako ako UprightDecoder). Takýto rámček sedí na ľubovoľne zmenšenej kópii fotky, preto sa
// takto prenáša medzi telefónmi. Prijímateľ ho prepočíta na absolútne pixely svojej kópie cez
// toAbsolute(šírka, výška) s rozmermi bitmapy, nad ktorou detekuje (UprightDecoder.uprightSize).
// Čistá logika bez Androidu (testovaná v RelBoxTest).
data class RelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    // Absolútny rámček [left, top, right, bottom] pre obrázok width × height (zaokrúhlené, orezané do obrázka).
    fun toAbsolute(width: Int, height: Int): IntArray {
        val l = toPixel(left, width)
        val t = toPixel(top, height)
        val r = maxOf(l, toPixel(right, width))
        val b = maxOf(t, toPixel(bottom, height))
        return intArrayOf(l, t, r, b)
    }

    // Otočenie relatívneho rámčeka rovnako ako UprightDecoder.decode/mapBox: najprv vodorovné
    // zrkadlenie, potom otočenie v smere hodín o rotationDegrees (0/90/180/270).
    fun transformed(rotationDegrees: Int, mirrored: Boolean): RelBox {
        val l = if (mirrored) 1f - right else left
        val r = if (mirrored) 1f - left else right
        return when (normalizeDegrees(rotationDegrees)) {
            DEG_90 -> RelBox(1f - bottom, l, 1f - top, r)
            DEG_180 -> RelBox(1f - r, 1f - bottom, 1f - l, 1f - top)
            DEG_270 -> RelBox(top, 1f - r, bottom, 1f - l)
            else -> RelBox(l, top, r, bottom)
        }
    }

    // Opačný prevod k transformed(): z upright priestoru späť do surového (neotočeného) obrázka.
    fun untransformed(rotationDegrees: Int, mirrored: Boolean): RelBox {
        val unrotated = transformed(FULL_TURN - normalizeDegrees(rotationDegrees), false)
        return if (mirrored) unrotated.transformed(0, true) else unrotated
    }

    // Otočenie a zrkadlenie, ktoré UprightDecoder aplikuje pre danú EXIF orientáciu.
    data class Transform(val rotationDegrees: Int, val mirrored: Boolean)

    companion object {
        const val DEG_90 = 90
        const val DEG_180 = 180
        const val DEG_270 = 270
        private const val FULL_TURN = 360

        // Hodnoty EXIF tagu Orientation (zhodné s androidx ExifInterface.ORIENTATION_*).
        const val EXIF_UNDEFINED = 0
        const val EXIF_NORMAL = 1
        const val EXIF_FLIP_HORIZONTAL = 2
        const val EXIF_ROTATE_180 = 3
        const val EXIF_FLIP_VERTICAL = 4
        const val EXIF_TRANSPOSE = 5
        const val EXIF_ROTATE_90 = 6
        const val EXIF_TRANSVERSE = 7
        const val EXIF_ROTATE_270 = 8

        private val IDENTITY = Transform(0, false)

        // Relatívny rámček z absolútneho [left, top, right, bottom] v obrázku width × height (orezané do 0–1).
        fun fromAbsolute(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): RelBox {
            require(width > 0 && height > 0) { "neplatné rozmery $width×$height" }
            return RelBox(
                toRelative(left, width),
                toRelative(top, height),
                toRelative(right, width),
                toRelative(bottom, height),
            )
        }

        // Rovnaké mapovanie EXIF orientácie ako UprightDecoder.decode a Glide. Zrkadlí sa PRED otočením:
        // 5 (TRANSPOSE, (x, y) → (y, x)) = zrkadlenie + 270°, 7 (TRANSVERSE) = zrkadlenie + 90° (E1 oprava,
        // predtým naopak — o 180° oproti zobrazeniu; staré rámčeky preklápa FaceMetaBackfill).
        fun exifTransform(orientation: Int): Transform = when (orientation) {
            EXIF_ROTATE_90 -> Transform(DEG_90, false)
            EXIF_ROTATE_180 -> Transform(DEG_180, false)
            EXIF_ROTATE_270 -> Transform(DEG_270, false)
            EXIF_FLIP_HORIZONTAL -> Transform(0, true)
            EXIF_FLIP_VERTICAL -> Transform(DEG_180, true)
            EXIF_TRANSPOSE -> Transform(DEG_270, true)
            EXIF_TRANSVERSE -> Transform(DEG_90, true)
            else -> IDENTITY
        }

        // Rozmery po otočení: pri 90/270 sa šírka a výška vymenia.
        fun uprightSize(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): IntArray =
            when (normalizeDegrees(rotationDegrees)) {
                DEG_90, DEG_270 -> intArrayOf(rawHeight, rawWidth)
                else -> intArrayOf(rawWidth, rawHeight)
            }

        // Rovnaký výpočet inSampleSize ako UprightDecoder.decode (mocnina 2, kým strana > maxSize).
        fun sampleSize(rawWidth: Int, rawHeight: Int, maxSize: Int): Int {
            require(maxSize > 0) { "maxSize musí byť kladné" }
            var sample = 1
            while (rawWidth / sample > maxSize || rawHeight / sample > maxSize) sample *= 2
            return sample
        }

        // Odhad rozmerov bitmapy, ktorú vráti UprightDecoder.decode(path, maxSize) (bez dekódovania pixelov).
        // Dekodér pri nedeliteľných rozmeroch môže zaokrúhliť nahor; rozdiel je najviac 1 px.
        fun decodedUprightSize(rawWidth: Int, rawHeight: Int, maxSize: Int, rotationDegrees: Int): IntArray {
            val sample = sampleSize(rawWidth, rawHeight, maxSize)
            val w = maxOf(1, rawWidth / sample)
            val h = maxOf(1, rawHeight / sample)
            return uprightSize(w, h, rotationDegrees)
        }

        private fun normalizeDegrees(deg: Int): Int = ((deg % FULL_TURN) + FULL_TURN) % FULL_TURN

        private fun toRelative(value: Int, size: Int): Float = (value.toFloat() / size).coerceIn(0f, 1f)

        private fun toPixel(value: Float, size: Int): Int = (value * size).roundToInt().coerceIn(0, size)
    }
}
