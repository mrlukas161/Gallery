package org.fossify.gallery.faces

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.family.BoxSpace
import org.fossify.gallery.family.ContentKeyStore
import org.fossify.gallery.family.FaceMatch
import org.fossify.gallery.family.FaceSpaceRules
import org.fossify.gallery.family.FamilyDatabase
import org.fossify.gallery.family.FamilyFaces
import org.fossify.gallery.family.RelBox
import org.fossify.gallery.family.StoredBox
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.HeavySlot
import java.io.File

// E1 — lenivé doplnenie nových stĺpcov faces.db v3 starým tváram (Room migrácia ich len pridala s NULL/0,
// lebo nevie čítať súbory ani EXIF). Spúšťa ho IndexingService (TASK_FACE_META) pri nabíjaní
// (ProcessingPolicy, rovnako ako prepočet odtlačkov v2 — FaceReembedV2), ručne kedykoľvek. Dá sa prerušiť;
// pokračuje tam, kde skončil (pracuje sa len s riadkami, ktorým údaj chýba). Všetko NA MIESTE cez UPDATE —
// id tvárí, menovky ani odtlačky sa nemenia.
//  1) bez modelov: embed_version (verzia uložená vo faces.db), taken_at (MediaStore DATE_TAKEN / EXIF;
//     0 = chýba) a box_space (FaceBoxSpace.derive): fotka bez otočenia → 2; inak len keď je upright priestor
//     istý — detekcia od 0.47 (dátum), geometria rámčeka alebo uložené overenie odtlačkom (D3, face_space).
//     Upright v1 na fotke s EXIF 5/7 sa pritom otočí o 180° (oprava UprightDecoder). Surové a nejasné
//     rámčeky ostanú 0 (dve hypotézy ako doteraz).
//  2) s modelmi (landmarker a detektor, miesto v HeavyGate): kvalita tvárí so známym priestorom rámčeka
//     (FaceAligner.analyzeDetected — rovnaká cesta ako pri indexovaní, bez odtlačku).
// Fotky, ktoré sa nedajú prečítať (zmazané, nepripojená karta), sa preskočia; ich riadky ostanú s NULL
// (brána kvality ich berie ako „prejde“, vekové váhy ako „bez dátumu“).
@Suppress("TooManyFunctions") // kroky jedného behu (beh, dva kroky, fotka, odvodenie priestoru, stav)
object FaceMetaBackfill {
    @Volatile
    var isRunning = false
        private set

    private const val PREFS = "galeria_faces"

    // čas začiatku posledného úplne dokončeného behu; potom už stačí kontrolovať novšie tváre
    private const val KEY_PASS_AT = "face_meta_pass_at"
    private const val MAX_DECODE = FaceMatch.DECODE_MAX_SIZE
    private const val MAX_ERROR_LEN = 140

    // Treba ešte niečo doplniť? (rýchle — prefs a jeden COUNT; volať z pozadia)
    fun isPending(context: Context): Boolean = try {
        val passAt = prefs(context).getLong(KEY_PASS_AT, 0L)
        passAt == 0L || FacesDatabase.getInstance(context).FaceDao().countNewNeedingMeta(passAt) > 0
    } catch (ignored: Throwable) {
        false
    }

    @Suppress("TooGenericExceptionCaught") // chyba ľubovoľného modelu sa len ohlási, beh pokračuje nabudúce
    fun run(
        context: Context,
        onWaiting: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (finished: Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val app = context.applicationContext
        ensureBackgroundThread {
            var finished = false
            var error: String? = null
            BackgroundPriority.run {
                val models = FaceModels(app, HeavySlot({ isRunning }, onWaiting))
                try {
                    val startedAt = System.currentTimeMillis()
                    finished = runAll(app, models, onProgress)
                    if (finished) prefs(app).edit().putLong(KEY_PASS_AT, startedAt).apply()
                } catch (ignored: HeavyGate.Stopped) {
                    finished = false
                } catch (e: Throwable) {
                    error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(MAX_ERROR_LEN) } ?: "")
                } finally {
                    models.close()
                    isRunning = false
                }
            }
            val err = error
            if (err != null) onError(err) else onDone(finished)
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun runAll(app: Context, models: FaceModels, onProgress: (Int, Int) -> Unit): Boolean {
        val dao = FacesDatabase.getInstance(app).FaceDao()
        // verzia odtlačkov sa dá doplniť len mimo prepočtu na v2 (inak by sa staré v1 označili, hneď
        // ako ich prepnutie prepíše — finalizeSwitch verziu zapisuje sám)
        if (!FaceEmbedVersion.isPending(app)) dao.fillEmbedVersion(FaceEmbedVersion.storedVersion())
        val metaPaths = dao.getPathsNeedingMeta()
        val total = metaPaths.size + dao.getPathsNeedingQuality().size
        var done = 0
        onProgress(done, total)
        val family = FamilyDatabase.getInstance(app)
        val spaces = FamilyFaces(app, dao, ContentKeyStore(family.contentKeyDao()), family.faceSpaceDao())
        val dates = FaceTakenAt.batch(app, metaPaths.filter { File(it).isFile })
        for (path in metaPaths) {
            if (!isRunning) return false
            if (File(path).isFile) fillMeta(dao, spaces, path, dates[path] ?: FaceTakenAt.UNKNOWN)
            done++
            onProgress(done, total)
        }
        // kvalita až po priestore rámčekov (preklopené v1 → v2 sú už na správnom mieste)
        for (path in dao.getPathsNeedingQuality()) {
            if (!isRunning) return false
            if (File(path).isFile) fillQuality(dao, models, path)
            done++
            onProgress(done, total)
        }
        return isRunning
    }

    // 1. krok jednej fotky: dátum a priestor rámčekov
    private fun fillMeta(dao: FaceDao, spaces: FamilyFaces, path: String, takenAt: Long) {
        try {
            dao.fillTakenAt(path, takenAt)
            val faces = dao.getFacesForPath(path).filter { it.boxSpace == FaceBoxSpace.UNKNOWN }
            if (faces.isEmpty()) return
            val geometry = RelinkData.rawGeometry(path) ?: return
            val transform = RelBox.exifTransform(geometry[2])
            val upright = RelBox.decodedUprightSize(geometry[0], geometry[1], MAX_DECODE, transform.rotationDegrees)
            for (f in faces) {
                val id = f.id
                val derived = FaceBoxSpace.derive(transform, uprightV1(f, geometry, transform, spaces))
                if (id == null || derived == null) continue
                val box = intArrayOf(f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom)
                val b = if (derived.rotate180) FaceBoxSpace.rotate180(box, upright[0], upright[1]) else box
                dao.updateBoxSpace(id, b[0], b[1], b[2], b[b.size - 1], derived.space)
            }
        } catch (ignored: RuntimeException) {
            // fotka sa skúsi pri ďalšom behu
        }
    }

    // je rámček v upright priestore starého dekodéra? (dátum detekcie, geometria, uložené overenie D3)
    private fun uprightV1(
        f: FaceEntity,
        geometry: IntArray,
        transform: RelBox.Transform,
        spaces: FamilyFaces,
    ): Boolean? {
        val stored = StoredBox(f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom, f.detectedAt)
        val cached = try {
            spaces.cachedSpace(f)
        } catch (ignored: RuntimeException) {
            null
        }
        return when (FaceSpaceRules.resolve(stored, geometry[0], geometry[1], transform, cached)) {
            BoxSpace.UPRIGHT -> true
            BoxSpace.RAW -> false
            else -> null
        }
    }

    // 2. krok jednej fotky: kvalita tvárí so známym priestorom rámčeka
    private fun fillQuality(dao: FaceDao, models: FaceModels, path: String) {
        val faces = dao.getFacesForPath(path).filter { it.quality == null && FaceBoxSpace.isKnown(it.boxSpace) }
        if (faces.isEmpty()) return
        val res = try {
            UprightDecoder.decode(path, MAX_DECODE)
        } catch (ignored: OutOfMemoryError) {
            null
        } ?: return
        val bmp = res.bitmap
        val full = FullResCrop.open(path, MAX_DECODE, bmp.width, bmp.height)
        try {
            val landmarker = models.landmarker()
            val detector = models.detector()
            for (f in faces) {
                val d = FaceDetectionHelper.DetectedFace(
                    f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom, f.score, 0f, 0f, 0f, 0f, false,
                )
                val id = f.id
                val a = FaceAligner.analyzeDetected(bmp, d, landmarker, detector, full)
                if (id == null || a == null) continue
                val m = FaceStore.metaOf(d, a.method, a.points, a.sharpness, res.sample)
                dao.updateQuality(id, m.quality, m.yaw, m.pitch, m.sharpness, m.method)
            }
        } catch (e: HeavyGate.Stopped) {
            throw e
        } catch (ignored: RuntimeException) {
            // fotka sa skúsi pri ďalšom behu
        } finally {
            full?.close()
            bmp.recycle()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
