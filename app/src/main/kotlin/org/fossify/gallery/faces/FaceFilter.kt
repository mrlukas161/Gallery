package org.fossify.gallery.faces

// Jediné miesto pre filter „dobrých" tvárí. Uvoľnené, aby nechýbali reálne tváre
// (radšej zopár falošných než stratiť skutočné). Neskôr môže byť posuvník v nastaveniach.
object FaceFilter {
    const val MIN_SCORE = 0.5f
    const val MIN_SIZE = 15

    fun isGood(f: FaceEntity): Boolean =
        f.score >= MIN_SCORE && (f.bboxRight - f.bboxLeft) >= MIN_SIZE
}
