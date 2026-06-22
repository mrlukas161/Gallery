package org.fossify.gallery.faces

// Skupina tvárí = jedna osoba. cover = najkvalitnejšia tvár (na avatar), photoPaths = fotky osoby.
data class Person(val faces: List<FaceEntity>) {
    val cover: FaceEntity = faces.maxByOrNull { it.score } ?: faces.first()
    val photoPaths: List<String> = faces.map { it.mediaFullPath }.distinct()
    val faceCount: Int = faces.size
}
