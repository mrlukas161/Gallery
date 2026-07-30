package org.fossify.gallery.helpers

import android.content.Context

// Prah istoty, od ktorého sa tvár automaticky zaradí do albumu osoby (bez ručného potvrdzovania).
// 0 = vypnuté (len ručne potvrdené). Vyššia hodnota = menej fotiek, ale takmer isto správne.
object FaceAuto {
    private const val PREFS = "galeria_faces"
    private const val KEY = "auto_face_threshold"

    const val OFF = 0f
    const val STRICT = 0.80f   // takmer isto správne
    const val NORMAL = 0.72f   // vyvážené (predvolené)
    const val LOOSE = 0.65f    // viac fotiek, občas niekto cudzí

    fun threshold(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, NORMAL)

    fun setThreshold(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, value).apply()
    }

    fun labelRes(value: Float): Int = when {
        value <= 0f -> org.fossify.gallery.R.string.face_auto_off
        value >= STRICT -> org.fossify.gallery.R.string.face_auto_strict
        value >= NORMAL -> org.fossify.gallery.R.string.face_auto_normal
        else -> org.fossify.gallery.R.string.face_auto_loose
    }
}
