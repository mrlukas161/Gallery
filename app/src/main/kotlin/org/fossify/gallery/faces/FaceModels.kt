package org.fossify.gallery.faces

import android.content.Context
import org.fossify.gallery.helpers.HeavySlot

// Modely tvárí (detektor, body tváre, odtlačok) jedného behu — načítajú sa LENIVO, až keď ich prvá
// fotka naozaj potrebuje; keď nie je čo spracovať, nenačíta sa nič. Pred prvým modelom si beh vezme
// miesto v HeavyGate (najviac jedna ťažká úloha naraz). Zastavenie počas čakania vyhodí
// HeavyGate.Stopped. close() vo finally behu zavrie, čo sa načítalo, a uvoľní miesto.
// Inštancia patrí jednému vláknu (beh indexera).
internal class FaceModels(private val context: Context, private val slot: HeavySlot) : AutoCloseable {
    private var detector: FaceDetectionHelper? = null
    private var embedder: FaceEmbedder? = null
    private var landmarker: FaceLandmarkHelper? = null

    fun detector(): FaceDetectionHelper = detector ?: load { FaceDetectionHelper(context) }.also { detector = it }

    fun embedder(): FaceEmbedder = embedder ?: load { FaceEmbedder(context) }.also { embedder = it }

    fun landmarker(): FaceLandmarkHelper = landmarker ?: load { FaceLandmarkHelper(context) }.also { landmarker = it }

    private inline fun <T> load(create: () -> T): T {
        slot.require()
        return create()
    }

    override fun close() {
        try {
            detector?.close()
            embedder?.close()
            landmarker?.close()
        } finally {
            detector = null
            embedder = null
            landmarker = null
            slot.close()
        }
    }
}
