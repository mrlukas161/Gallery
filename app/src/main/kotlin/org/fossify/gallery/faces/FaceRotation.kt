package org.fossify.gallery.faces

import android.content.Context
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.family.ContentKeyStore
import org.fossify.gallery.family.FaceMatch
import org.fossify.gallery.family.FamilyDatabase
import java.io.IOException

// A6: tváre po otočení alebo prepísaní fotky v appke (extensions/Activity.kt: saveRotatedImageToFile,
// tryRotateByExif; editor). Rámčeky sa otočia NA MIESTE v transakcii (id, menovky aj odtlačky ostanú),
// zabudne sa obsahový kľúč (EXIF otočenie mení obsah, ale veľkosť/mtime často nie) a zneplatnia sa
// výrezy tvárí v cache. Keď sa rámčeky nedajú otočiť bezpečne (staré rámčeky v surovom priestore,
// zrkadlené EXIF, nečakaný výsledok, orezanie v editore), fotka sa zaradí na prepracovanie
// (FaceRefreshQueue → RefreshFaces.refreshPaths) — tam sa menovky prenesú cez prekryv rámčekov (IoU).
object FaceRotation {
    private const val MAX_DECODE = FaceMatch.DECODE_MAX_SIZE
    private const val LEFT = 0
    private const val TOP = 1
    private const val RIGHT = 2
    private const val BOTTOM = 3

    // Stav fotky PRED otočením (EXIF orientácia + rozmery upright bitmapy, v ktorých sú rámčeky).
    // Volať tesne pred zápisom otočenia. null = nečitateľná (po otočení sa vtedy fotka prepracuje).
    fun snapshot(path: String): FaceBoxRotation.State? = try {
        UprightDecoder.uprightSize(path, MAX_DECODE)?.let { FaceBoxRotation.State(exifOrientation(path), it[0], it[1]) }
    } catch (ignored: Throwable) {
        null
    }

    // Fotka na ceste `path` sa úspešne otočila o `degrees` (v smere hodín). pixelsRewritten = pixely
    // sa zapísali nanovo (nie JPEG), inak sa zmenil len EXIF tag.
    fun onRotated(
        context: Context,
        path: String,
        before: FaceBoxRotation.State?,
        degrees: Int,
        pixelsRewritten: Boolean,
    ) {
        val app = context.applicationContext
        runOffMain {
            try {
                rotateFaces(app, path, before, degrees, pixelsRewritten)
            } catch (ignored: Throwable) {
                // otočenie fotky samo prebehlo — tváre nesmú zhodiť appku
            }
        }
    }

    // Obsah fotky sa prepísal inak než čistým otočením (editor: orezanie, kreslenie, filter, uloženie
    // cez existujúci súbor). Rámčeky nemusia sedieť → zabudnúť kľúč, výrezy a prepracovať.
    fun onRewritten(context: Context, path: String) {
        val app = context.applicationContext
        runOffMain {
            try {
                forgetCaches(app, path)
                val faceCount = FacesDatabase.getInstance(app).FaceDao().getFaceCountForPath(path)
                if (faceCount > 0) FaceRefreshQueue.add(app, path)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun rotateFaces(
        app: Context,
        path: String,
        before: FaceBoxRotation.State?,
        degrees: Int,
        pixelsRewritten: Boolean,
    ) {
        forgetCaches(app, path)
        val db = FacesDatabase.getInstance(app)
        val dao = db.FaceDao()
        val faces = dao.getFacesForPath(path)
        val detectedAt = faces.map { it.detectedAt }
        val spaces = faces.map { it.boxSpace }
        val decision = FaceBoxRotation.decide(before, snapshot(path), degrees, pixelsRewritten, detectedAt, spaces)
        when (decision) {
            FaceBoxRotation.Decision.Unchanged -> Unit
            FaceBoxRotation.Decision.Refresh -> FaceRefreshQueue.add(app, path)
            is FaceBoxRotation.Decision.Rotate -> db.runInTransaction(
                Runnable {
                    for (f in dao.getFacesForPath(path)) {
                        val id = f.id ?: continue
                        val box = FaceBoxRotation.rotate(
                            f.bboxLeft, f.bboxTop, f.bboxRight, f.bboxBottom,
                            decision.width, decision.height, decision.degrees,
                        )
                        dao.updateBox(id, box[LEFT], box[TOP], box[RIGHT], box[BOTTOM])
                    }
                }
            )
        }
        // kľúč nového obsahu hneď, aby relinker (A5) našiel fotku aj po presune ešte pred ďalším spracovaním
        try {
            ContentKeyStore(FamilyDatabase.getInstance(app).contentKeyDao()).keyFor(path)
        } catch (ignored: Throwable) {
        }
    }

    private fun forgetCaches(app: Context, path: String) {
        try {
            ContentKeyStore(FamilyDatabase.getInstance(app).contentKeyDao()).forget(path)
        } catch (ignored: Throwable) {
        }
        FaceCropLoader.invalidate(path)
        FaceContextLoader.invalidate(path)
    }

    private fun exifOrientation(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (ignored: IOException) {
        ExifInterface.ORIENTATION_NORMAL // rovnako ako UprightDecoder: bez čitateľného EXIF sa neotáča
    }

    private fun runOffMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) ensureBackgroundThread(block) else block()
    }
}
