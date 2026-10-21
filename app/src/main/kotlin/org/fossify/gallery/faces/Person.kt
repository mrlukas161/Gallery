package org.fossify.gallery.faces

// Skupina tvárí zobrazená v UI.
//  id = null  → nepotvrdená auto-skupina (návrh)
//  id != null → potvrdená/pomenovaná osoba (z people.db)
// faces = tváre osoby (priradené + automaticky zaradené), manualFaceIds = tie s riadkom v priradeniach.
// familyFaceIds = podmnožina manualFaceIds s is_manual=false (prišli z rodinného prepojenia) — v ťažisku
// osoby majú nižšiu váhu (PersonGrouper.familyWeight).
// photosMissing = všetky tváre osoby sú skryté (fotky sa nenašli / sú mimo rozsahu), osoba sa ukáže sivo.
data class Person(
    val id: Long?,
    val name: String?,
    val faces: List<FaceEntity>,
    val manualFaceIds: Set<Long> = emptySet(),
    val familyFaceIds: Set<Long> = emptySet(),
    val photosMissing: Boolean = false,
) {
    val cover: FaceEntity? = faces.maxByOrNull { it.score }
    val photoPaths: List<String> = faces.map { it.mediaFullPath }.distinct()
    val faceCount: Int = faces.size
    val isConfirmed: Boolean = id != null
}
