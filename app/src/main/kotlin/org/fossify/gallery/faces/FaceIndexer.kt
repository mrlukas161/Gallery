package org.fossify.gallery.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.family.FamilyInbox
import java.io.File

// B2: indexovanie = detekcia + ODTLAČOK pre každú tvár (faces.db); po dokončení vráti počet tvárí
// a fotiek s tvárami. Beží na bežnom vlákne (robustnosť FGS/WorkManager = B4).
object FaceIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE_SIZE = 1024
    private const val CHANNEL_ID = "face_indexing"
    private const val NOTIF_ID = 49231
    private const val KEY_REPAIR_AT = "faces_embed_repair_at"
    private const val REPAIR_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val REPAIR_MAX_PHOTOS = 100

    fun index(
        context: Context,
        notify: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (faces: Int, photos: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var detector: FaceDetectionHelper? = null
            var embedder: FaceEmbedder? = null
            var landmarker: FaceLandmarkHelper? = null
            try {
                val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                // nové tváre len s id nad všetkými odkazmi z people.db/extras.db (poistka proti posunu id)
                if (!FacesDatabase.ensureIdsAboveReferences(appCtx)) {
                    throw IllegalStateException(appCtx.getString(R.string.c1_faces_refs_unreadable))
                }
                detector = FaceDetectionHelper(appCtx)
                embedder = FaceEmbedder(appCtx)
                landmarker = FaceLandmarkHelper(appCtx)
                if (notify) ensureChannel(appCtx)

                // A5: presunuté/premenované fotky preklopiť podľa obsahu PRED detekciou (inak duplikáty)
                FaceRelinker.relink(appCtx, dao) { isRunning }
                val processed = dao.getProcessedPaths().toHashSet()
                // vylúčené a zamknuté priečinky sa nespracúvajú (rozhodnutie Lukáša 20.10.2026);
                // už uložené tváre odtiaľ sa NEmažú — UI ich len skryje
                val scope = IndexScope(appCtx)
                val candidates = queryImages(appCtx).filter { it.second !in processed && scope.allows(it.second) }
                // tváre z prepojeného telefónu (F4) sa zapíšu hneď aj so značkou, detekcia sa nerobí
                val todo = FamilyInbox.withoutSupplied(appCtx, candidates, FamilyInbox.FACES) { it.second }
                // fotka bez značky „spracovaná", ale s tvárami v DB (značku zmazalo staré denné
                // upratovanie pri dočasne nedostupnom súbore, alebo pád medzi zápisom tvárí a značky):
                // tváre NEdetegovať nanovo, len doplniť značku — inak by vznikli nové id a menovky
                // v people.db by ukazovali do prázdna
                val withFaces = if (todo.isEmpty()) emptySet() else dao.getPathsWithFaces().toHashSet()
                val total = todo.size
                var done = 0
                for ((mediaStoreId, path) in todo) {
                    if (!isRunning) break
                    if (path in withFaces) {
                        try {
                            dao.insertIndexedPhoto(IndexedPhotoEntity(path, dao.getFaceCountForPath(path), System.currentTimeMillis()))
                        } catch (ignored: Throwable) {
                        }
                        done++
                        if (done % 5 == 0 || done == total) onProgress(done, total)
                        continue
                    }
                    var faceCount = 0
                    try {
                        val bmp = decodeDownscaled(path)
                        if (bmp != null) {
                            try {
                                val detected = detector.detect(bmp)
                                faceCount = detected.size
                                if (detected.isNotEmpty()) {
                                    val theEmbedder = embedder
                                    val theLandmarker = landmarker
                                    dao.insertFaces(detected.mapIndexed { i, f ->
                                        FaceEntity(
                                            id = null, mediaFullPath = path, mediaStoreId = mediaStoreId, faceIndex = i,
                                            bboxLeft = f.left, bboxTop = f.top, bboxRight = f.right, bboxBottom = f.bottom,
                                            score = f.score,
                                            embedding = FaceAligner.embedDetected(bmp, f, theLandmarker, theEmbedder, detector),
                                            detectedAt = System.currentTimeMillis(),
                                        )
                                    })
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                        dao.insertIndexedPhoto(IndexedPhotoEntity(path, faceCount, System.currentTimeMillis()))
                    } catch (ignored: Throwable) {
                        // jedna pokazená fotka nesmie zastaviť celé indexovanie
                    }
                    done++
                    if (done % 5 == 0 || done == total) {
                        onProgress(done, total)
                        if (notify) notifyProgress(appCtx, done, total)
                    }
                }
                cancelNotification(appCtx)
                if (isRunning) maybeRepairUnembedded(appCtx, dao, detector, embedder, landmarker)
                // (predtým sa tu po KAŽDOM behu zhlukovali všetky odtlačky pri 8 prahoch len pre
                // diagnostický reťazec, ktorý nikto nečítal — zbytočná záťaž CPU a batérie)
                onDone(safeFaceCount(dao), safePhotoCount(dao))
            } catch (e: Throwable) {
                cancelNotification(appCtx)
                onError(describe(e))
            } finally {
                detector?.close()
                embedder?.close()
                landmarker?.close()
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    // Raz denne skús doplniť odtlačok tváram, ktorým ho indexovanie nevypočítalo — bez neho sú
    // v appke neviditeľné a nedajú sa označiť. Obmedzené počtom fotiek, aby to nezaťažovalo
    // bežné (automatické) spracovanie knižnice; neopraviteľné sa skúsia znova o deň.
    private fun maybeRepairUnembedded(
        context: Context,
        dao: FaceDao,
        detector: FaceDetectionHelper,
        embedder: FaceEmbedder,
        landmarker: FaceLandmarkHelper,
    ) {
        try {
            val prefs = context.getSharedPreferences("galeria_faces", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_REPAIR_AT, 0L) < REPAIR_INTERVAL_MS) return
            prefs.edit().putLong(KEY_REPAIR_AT, System.currentTimeMillis()).apply()
            RefreshFaces.repairUnembedded(dao, detector, embedder, landmarker, REPAIR_MAX_PHOTOS) { isRunning }
        } catch (ignored: Throwable) {
        }
    }

    // Zostaví reťazec príčin (root-first), aby sme videli, čo presne zlyhalo.
    private fun describe(t: Throwable): String {
        val chain = ArrayList<Throwable>()
        var cur: Throwable? = t
        while (cur != null && chain.size < 5) {
            chain.add(cur)
            cur = cur.cause
        }
        return chain.asReversed().joinToString(" <- ") { e ->
            e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: "")
        }
    }

    private fun safeFaceCount(dao: FaceDao) = try { dao.getFaceCount() } catch (e: Throwable) { 0 }
    private fun safePhotoCount(dao: FaceDao) = try { dao.getPhotosWithFacesCount() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // vždy (aj keď kanál existuje) — systém tak premenuje kanál vytvorený staršou verziou
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.q1_channel_faces), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notifyProgress(context: Context, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.q1_notif_faces))
                .setContentText("$done / $total")
                .setProgress(total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (ignored: SecurityException) {
            // bez povolenia na notifikácie (Android 13+) — priebeh len nezobrazíme
        } catch (ignored: Throwable) {
        }
    }

    private fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        } catch (ignored: Throwable) {
        }
    }

    private fun queryImages(context: Context): List<Pair<Long, String>> {
        val list = ArrayList<Pair<Long, String>>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val data = cursor.getString(dataCol) ?: continue
                    list.add(id to data)
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }

    // VŽDY „na stojato" (EXIF) — inak detektor na fotkách na výšku tváre nenájde a výrezy sú otočené
    private fun decodeDownscaled(path: String): Bitmap? =
        UprightDecoder.decode(path, MAX_DECODE_SIZE)?.bitmap
}
