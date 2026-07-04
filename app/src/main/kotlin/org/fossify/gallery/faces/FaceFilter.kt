package org.fossify.gallery.faces

// Jediné miesto pre filter „dobrých" tvárí. Detektor beží na confidence 0.7, takže MIN_SCORE
// pod 0.7 nič nefiltroval -> prechádzali falošné detekcie na predmetoch/stenách. Zdvihnuté nad prah
// (0.82) + väčšia minimálna veľkosť, aby zmizli predmety. Ak by miznuli reálne tváre, znížiť.
object FaceFilter {
    const val MIN_SCORE = 0.82f
    const val MIN_SIZE = 24

    fun isGood(f: FaceEntity): Boolean =
        f.score >= MIN_SCORE && (f.bboxRight - f.bboxLeft) >= MIN_SIZE
}
