package org.fossify.gallery.faces

// Skupina tvárí zobrazená v UI.
//  id = null  → nepotvrdená auto-skupina (návrh)
//  id != null → potvrdená/pomenovaná osoba (z people.db)
// faces = tváre osoby (ručne priradené + automaticky navrhnuté), manualFaceIds = tie ručne potvrdené.
data class Person(
    val id: Long?,
    val name: String?,
    val faces: List<FaceEntity>,
    val manualFaceIds: Set<Long> = emptySet(),
) {
    val cover: FaceEntity? = faces.maxByOrNull { it.score }
    val photoPaths: List<String> = faces.map { it.mediaFullPath }.distinct()
    val faceCount: Int = faces.size
    val isConfirmed: Boolean = id != null
}
