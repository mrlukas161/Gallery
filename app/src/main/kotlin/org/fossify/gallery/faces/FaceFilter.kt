package org.fossify.gallery.faces

// Jediné miesto pre filter „dobrých" tvárí. Kompromis: nižšie prahy nájdu viac (aj menších/
// vzdialených) tvárí — Lukáš chce vidieť všetkých ľudí aj na skupinových fotkách. Cena = zopár
// falošných detekcií na predmetoch. Ak by ich bolo priveľa, zdvihnúť MIN_SCORE.
object FaceFilter {
    const val MIN_SCORE = 0.6f
    const val MIN_SIZE = 14

    fun isGood(f: FaceEntity): Boolean =
        f.score >= MIN_SCORE && (f.bboxRight - f.bboxLeft) >= MIN_SIZE

    // rovnaký filter už pri detekcii (pred zápisom do DB)
    fun isUsable(d: FaceDetectionHelper.DetectedFace): Boolean =
        d.score >= MIN_SCORE && (d.right - d.left) >= MIN_SIZE
}
