package org.fossify.gallery.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.family.FamilyInbox
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.HeavySlot
import java.io.File
import java.util.concurrent.atomic.AtomicLong

// B2: indexovanie = detekcia + ODTLAČOK pre každú tvár (faces.db); po dokončení vráti počet tvárí
// a fotiek s tvárami. Beží na bežnom vlákne (robustnosť FGS/WorkManager = B4).
object FaceIndexer {
    // A7: beh sa „privlastní“ cez compareAndSet(0, id) — dve súbežné volania nespustia dva behy naraz.
    // owner = id bežiaceho behu (0 = nebeží). stop() ho vynuluje; koniec behu ho vynuluje len vtedy,
    // keď je ešte jeho (compareAndSet), takže nový beh spustený hneď po stop() neprepíše.
    private val owner = AtomicLong(0)
    private val runIds = AtomicLong(0)

    val isRunning: Boolean
        get() = owner.get() != 0L

    private const val MAX_DECODE_SIZE = 1024
    private const val CHANNEL_ID = "face_indexing"
    private const val NOTIF_ID = 49231
    private const val KEY_REPAIR_AT = "faces_embed_repair_at"
    private const val REPAIR_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val REPAIR_MAX_PHOTOS = 100

    // Modely sa načítajú až pri prvej fotke, ktorú treba naozaj spracovať (FaceModels), a s miestom
    // v HeavyGate; onWaiting = beh čaká, kým dobehne iná ťažká úloha. Callbacky onDone/onError sa volajú
    // až PO zatvorení modelov a uvoľnení miesta — ďalšia úloha spustená z nich (aj na tom istom
    // vlákne) už pamäť po tvárach nemá obsadenú.
    // E3: multiScale = navyše detekcia malých tvárí v dlaždiciach (len pri nabíjaní alebo v Plnom profile,
    // rozhoduje IndexingService cez ProcessingPolicy.multiScale)
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun index(
        context: Context,
        notify: Boolean = true,
        multiScale: Boolean = false,
        onWaiting: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (faces: Int, photos: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        val myRun = runIds.incrementAndGet()
        if (!owner.compareAndSet(0, myRun)) return
        val active = { owner.get() == myRun }
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var error: String? = null
            var total = 0
            var done = 0
            BackgroundPriority.run {
                val models = FaceModels(appCtx, HeavySlot(active, onWaiting, onResume = { onProgress(done, total) }))
                try {
                    val dao = FacesDatabase.getInstance(appCtx).FaceDao()
                    // nové tváre len s id nad všetkými odkazmi z people.db/extras.db (poistka proti posunu id)
                    if (!FacesDatabase.ensureIdsAboveReferences(appCtx)) {
                        throw IllegalStateException(appCtx.getString(R.string.c1_faces_refs_unreadable))
                    }
                    if (notify) ensureChannel(appCtx)

                    // A5: presunuté/premenované fotky preklopiť podľa obsahu PRED detekciou (inak duplikáty)
                    FaceRelinker.relink(appCtx, dao, active)
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
                    total = todo.size
                    for ((mediaStoreId, path) in todo) {
                        if (!active()) break
                        if (path in withFaces) {
                            try {
                                dao.insertIndexedPhoto(IndexedPhotoEntity(path, dao.getFaceCountForPath(path), System.currentTimeMillis()))
                            } catch (ignored: Throwable) {
                            }
                            done++
                            if (done % 5 == 0 || done == total) onProgress(done, total)
                            continue
                        }
                        indexPhoto(appCtx, dao, models, mediaStoreId, path, multiScale)
                        done++
                        if (done % 5 == 0 || done == total) {
                            onProgress(done, total)
                            if (notify) notifyProgress(appCtx, done, total)
                        }
                    }
                    cancelNotification(appCtx)
                    if (active()) maybeRepairUnembedded(appCtx, dao, models, active)
                    // (predtým sa tu po KAŽDOM behu zhlukovali všetky odtlačky pri 8 prahoch len pre
                    // diagnostický reťazec, ktorý nikto nečítal — zbytočná záťaž CPU a batérie)
                } catch (ignored: HeavyGate.Stopped) {
                    cancelNotification(appCtx)
                } catch (e: Throwable) {
                    cancelNotification(appCtx)
                    error = describe(e)
                } finally {
                    models.close()
                    owner.compareAndSet(myRun, 0)
                }
            }
            val err = error
            if (err != null) {
                onError(err)
            } else {
                onDone(safeFaceCount(appCtx), safePhotoCount(appCtx))
            }
        }
    }

    // Jedna fotka: detekcia + FaceFilter + odtlačky v2 + zápis (počas prepočtu na v2 bez odtlačku).
    // Pokazená fotka nesmie zastaviť celé indexovanie; zastavenie počas čakania na miesto nezapíše značku.
    @Suppress("LongParameterList")
    private fun indexPhoto(
        appCtx: Context,
        dao: FaceDao,
        models: FaceModels,
        mediaStoreId: Long,
        path: String,
        multiScale: Boolean,
    ) {
        var faceCount = 0
        try {
            val res = decodeDownscaled(path)
            if (res != null) {
                val bmp = res.bitmap
                try {
                    faceCount = FaceStore.detectAndInsert(
                        appCtx, dao, path, mediaStoreId, bmp, MAX_DECODE_SIZE,
                        models.detector(), models.landmarker(), models.embedder(),
                        sample = res.sample, multiScale = multiScale,
                    )
                } finally {
                    bmp.recycle()
                }
            }
            dao.insertIndexedPhoto(IndexedPhotoEntity(path, faceCount, System.currentTimeMillis()))
        } catch (e: HeavyGate.Stopped) {
            throw e
        } catch (ignored: Throwable) {
        }
    }

    fun stop() {
        owner.set(0)
    }

    // Raz denne skús doplniť odtlačok tváram, ktorým ho indexovanie nevypočítalo — bez neho sú
    // v appke neviditeľné a nedajú sa označiť. Obmedzené počtom fotiek, aby to nezaťažovalo
    // bežné (automatické) spracovanie knižnice; neopraviteľné sa skúsia znova o deň.
    private fun maybeRepairUnembedded(
        context: Context,
        dao: FaceDao,
        models: FaceModels,
        active: () -> Boolean,
    ) {
        try {
            // počas prepočtu na v2 by oprava zapísala v2 vektor medzi v1 — doplní ho prepnutie
            if (FaceEmbedVersion.isPending(context)) return
            val prefs = context.getSharedPreferences("galeria_faces", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_REPAIR_AT, 0L) < REPAIR_INTERVAL_MS) return
            prefs.edit().putLong(KEY_REPAIR_AT, System.currentTimeMillis()).apply()
            RefreshFaces.repairUnembedded(dao, models, REPAIR_MAX_PHOTOS, active)
        } catch (e: HeavyGate.Stopped) {
            throw e
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

    // aj otvorenie DB v try — callback sa volá mimo hlavného try behu
    private fun safeFaceCount(context: Context) =
        try { FacesDatabase.getInstance(context).FaceDao().getFaceCount() } catch (e: Throwable) { 0 }
    private fun safePhotoCount(context: Context) =
        try { FacesDatabase.getInstance(context).FaceDao().getPhotosWithFacesCount() } catch (e: Throwable) { 0 }

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
    private fun decodeDownscaled(path: String): UprightDecoder.Result? =
        UprightDecoder.decode(path, MAX_DECODE_SIZE)
}
