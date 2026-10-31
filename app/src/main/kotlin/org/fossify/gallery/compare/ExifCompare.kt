package org.fossify.gallery.compare

// Riadky panela „Údaje vedľa seba“ (ⓘ) pre fotky v paneloch A a B (ExifCompareTest). Texty hodnôt
// sú hotové, názvy riadkov a „áno/nie“ dopĺňa Android vrstva. better: −1 = lepšia A, 1 = lepšia B, 0 = nič;
// differs = hodnoty sa líšia (zvýrazní sa aj bez „lepšej“).
enum class InfoField { TIME, RESOLUTION, SIZE, ISO, SHUTTER, APERTURE, FOCAL, CAMERA, GPS, FOLDER }

data class InfoRow(val field: InfoField, val a: String, val b: String, val better: Int, val differs: Boolean)

object ExifCompare {
    const val NONE = "–"
    private const val SAME_RES = 0.98

    // folderName: čitateľný názov priečinka (Android vrstva), yes/no: preložené „áno“/„nie“
    fun rows(a: PhotoInfo, b: PhotoInfo, folderName: (String) -> String, yes: String, no: String): List<InfoRow> {
        val time = { p: PhotoInfo -> CompareFormat.time(p.taken) }
        val size = { p: PhotoInfo -> CompareFormat.size(p.bytes) }
        val iso = { p: PhotoInfo -> text(p.iso) { it.toString() } }
        val shutter = { p: PhotoInfo -> text(p.exposureTime) { CompareFormat.exposureTime(it) } }
        val aperture = { p: PhotoInfo -> text(p.fNumber) { CompareFormat.aperture(it) } }
        val focal = { p: PhotoInfo -> text(p.focalLength) { CompareFormat.focal(it) } }
        val gps = { p: PhotoInfo -> if (p.hasGps) yes else no }
        val folder = { p: PhotoInfo -> folderName(p.path) }
        return listOf(
            row(InfoField.TIME, a, b, time, 0),
            row(InfoField.RESOLUTION, a, b, ::resolution, higherRes(a, b)),
            row(InfoField.SIZE, a, b, size, 0),
            row(InfoField.ISO, a, b, iso, lower(a.iso?.toDouble(), b.iso?.toDouble())),
            row(InfoField.SHUTTER, a, b, shutter, lower(a.exposureTime, b.exposureTime)),
            row(InfoField.APERTURE, a, b, aperture, 0),
            row(InfoField.FOCAL, a, b, focal, 0),
            row(InfoField.CAMERA, a, b, { it.camera ?: NONE }, 0),
            row(InfoField.GPS, a, b, gps, 0),
            row(InfoField.FOLDER, a, b, folder, originalFolder(a, b)),
        )
    }

    private fun <T> text(value: T?, format: (T) -> String): String = value?.let(format) ?: NONE

    private fun row(field: InfoField, a: PhotoInfo, b: PhotoInfo, value: (PhotoInfo) -> String, better: Int): InfoRow {
        val va = value(a)
        val vb = value(b)
        return InfoRow(field, va, vb, better, va != vb)
    }

    private fun resolution(p: PhotoInfo) = if (p.pixels > 0) "${p.width} × ${p.height}" else NONE

    // väčšie rozlíšenie je lepšie (rozdiel nad ~2 %)
    fun higherRes(a: PhotoInfo, b: PhotoInfo): Int = when {
        a.pixels <= 0 || b.pixels <= 0 -> 0
        a.pixels * SAME_RES > b.pixels -> -1
        b.pixels * SAME_RES > a.pixels -> 1
        else -> 0
    }

    // nižšie ISO / kratší čas uzávierky = menej šumu a rozmazania
    fun lower(a: Double?, b: Double?): Int = when {
        a == null || b == null || a == b -> 0
        a < b -> -1
        else -> 1
    }

    // originál z Fotoaparátu je lepší ako kópia z WhatsAppu/Stiahnutých
    private fun originalFolder(a: PhotoInfo, b: PhotoInfo): Int {
        val ka = rank(CompareFormat.folderKind(a.path))
        val kb = rank(CompareFormat.folderKind(b.path))
        return ka.compareTo(kb)
    }

    private fun rank(kind: FolderKind) = when (kind) {
        FolderKind.CAMERA -> 0
        FolderKind.OTHER -> 1
        else -> 2
    }
}
