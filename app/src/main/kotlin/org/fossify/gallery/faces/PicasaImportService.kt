package org.fossify.gallery.faces

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavySlot
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.zip.ZipFile

// Import "vzorov" tvárí z Picasy: ZIP (manifest.json + person_xx/crop_*.jpg).
// Pre každý výrez: zarovnanie (FaceLandmarker 5 bodov, inak BlazeFace 2 body) -> odtlačok MobileFaceNet
// -> vzor osoby. Tým vzniknú odtlačky v ROVNAKOM priestore ako telefónne tváre (v2).
//  - Nezarovnané výrezy (len zmenšené, FaceAligner.MR) sa neukladajú — v inom priestore by kazili ťažisko.
//  - Každá osoba sa zapíše v jednej transakcii (osoba + jej vzory); manifest až po dokončení celého
//    importu. Prerušený import pokračuje od nedokončených osôb (bez zdvojených vzorov).
//  - Výrezy sa uchovajú v súkromnom úložisku appky (files/picasa_crops/<id vzoru>.jpg), aby sa vzory dali
//    pri budúcej zmene odtlačkov prepočítať bez nového ZIP-u.
//  - Kým beží prepočet odtlačkov na v2, import čaká (nové v2 vzory by sa miešali s v1 tvárami).
//  - Import pred prepnutím na v2 (v1 vzory, teraz odložené) sa nepočíta ako „už naimportované".
@Suppress("TooManyFunctions") // kroky importu (ZIP, odtlačky, zápis osoby, výrezy, priebeh po priečinkoch)
object PicasaImportService {
    const val SOURCE = "picasa"
    private const val CROPS_DIR = "picasa_crops"
    private const val PROGRESS_PREFS = "galeria_picasa_import"

    @Volatile
    var isRunning = false
        private set

    fun import(
        context: Context,
        zipUri: Uri,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (persons: Int, anchors: Int, skipped: Int, alreadyImported: Boolean) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        val appCtx = context.applicationContext
        if (FaceEmbedVersion.isPending(appCtx)) {
            onError(appCtx.getString(R.string.tb_picasa_wait_v2))
            return
        }
        isRunning = true
        ensureBackgroundThread {
            val priority = BackgroundPriority.lower()
            // ťažká úloha: modely až po prečítaní manifestu a s miestom v HeavyGate (najviac jedna naraz)
            var slot: HeavySlot? = null
            var landmarker: FaceLandmarkHelper? = null
            var embedder: FaceEmbedder? = null
            var detector: FaceDetectionHelper? = null
            var tmp: File? = null
            try {
                // 1) skopíruj ZIP do cache (ZipFile potrebuje náhodný prístup k súboru)
                val zipFile = copyZip(appCtx, zipUri) { tmp = it }

                val db = PeopleDatabase.getInstance(appCtx)
                val dao = db.PeopleDao()
                ZipFile(zipFile).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json")
                        ?: throw IllegalStateException("V ZIP-e chýba manifest.json")
                    val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    val hash = sha256(manifestBytes)

                    val previous = dao.findImportManifest(hash)
                    if (previous != null &&
                        EmbedVersionRules.manifestStillValid(previous.importedAt, FaceEmbedVersion.switchedAt(appCtx))
                    ) {
                        onDone(0, 0, 0, true)
                        return@ensureBackgroundThread
                    }

                    val root = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val people = root.getJSONArray("people")
                    var totalCrops = 0
                    for (i in 0 until people.length()) {
                        totalCrops += people.getJSONObject(i).optJSONArray("crops")?.length() ?: 0
                    }

                    val heavy = HeavySlot({ isRunning }, onWait = { onProgress(0, totalCrops) }).also { slot = it }
                    if (!heavy.ensure()) {
                        onError(appCtx.getString(R.string.tb_picasa_interrupted))
                        return@ensureBackgroundThread
                    }
                    val lm = FaceLandmarkHelper(appCtx).also { landmarker = it }
                    val emb = FaceEmbedder(appCtx).also { embedder = it }
                    val det = FaceDetectionHelper(appCtx).also { detector = it }
                    val finished = finishedFolders(appCtx, hash)
                    var done = 0
                    var anchors = 0
                    var skipped = 0
                    var personsTouched = 0
                    var complete = true

                    for (i in 0 until people.length()) {
                        if (!isRunning) {
                            complete = false
                            break
                        }
                        val po = people.getJSONObject(i)
                        val name = po.optString("name").trim()
                        val folder = po.optString("folder")
                        val crops = po.optJSONArray("crops")
                        if (name.isEmpty() || folder.isEmpty() || crops == null) continue
                        if (folder in finished) {
                            done += crops.length()
                            continue
                        }

                        // odtlačky osoby mimo transakcie (pomalé), zápis potom naraz
                        val ready = ArrayList<Pair<ByteArray, FloatArray>>()
                        for (j in 0 until crops.length()) {
                            if (!isRunning) break
                            val entry = zip.getEntry("$folder/${crops.optString(j)}")
                            if (entry == null) {
                                skipped++
                            } else {
                                val vec = embedEntry(zip.getInputStream(entry).use { it.readBytes() }, lm, emb, det)
                                if (vec != null) ready.add(vec) else skipped++
                            }
                            done++
                            if (done % 5 == 0 || done == totalCrops) onProgress(done, totalCrops)
                        }
                        if (!isRunning) {
                            complete = false
                            break
                        }
                        if (ready.isNotEmpty()) {
                            val ids = storePerson(db, dao, name, ready)
                            saveCrops(appCtx, ids, ready)
                            anchors += ids.size
                            personsTouched++
                        }
                        markFolderFinished(appCtx, hash, folder)
                    }

                    if (!complete) {
                        onError(appCtx.getString(R.string.tb_picasa_interrupted))
                        return@ensureBackgroundThread
                    }
                    // manifest až po úplnom dokončení (aj nad starým manifestom v1 importu)
                    db.openHelper.writableDatabase.execSQL(
                        "INSERT OR REPLACE INTO import_manifests (manifest_hash, imported_at, person_count) " +
                            "VALUES (?, ?, ?)",
                        arrayOf<Any>(hash, System.currentTimeMillis(), personsTouched),
                    )
                    clearFinishedFolders(appCtx, hash)
                    FaceEmbedVersion.clearPicasaReimport(appCtx)
                    onDone(personsTouched, anchors, skipped, false)
                }
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: ""))
            } finally {
                landmarker?.close()
                embedder?.close()
                detector?.close()
                slot?.close()
                try {
                    tmp?.delete()
                } catch (ignored: Throwable) {
                }
                BackgroundPriority.restore(priority)
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    // ZIP do cache (ZipFile potrebuje náhodný prístup k súboru); onFile hneď, aby ho finally zmazal aj pri chybe
    private fun copyZip(context: Context, zipUri: Uri, onFile: (File) -> Unit): File {
        val zipFile = File(context.cacheDir, "picasa_import_${System.currentTimeMillis()}.zip").also(onFile)
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            zipFile.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("Nepodarilo sa otvoriť ZIP")
        return zipFile
    }

    // (bajty výrezu, odtlačok) alebo null, keď sa výrez nedá dekódovať alebo zarovnať
    private fun embedEntry(
        bytes: ByteArray,
        landmarker: FaceLandmarkHelper,
        embedder: FaceEmbedder,
        detector: FaceDetectionHelper,
    ): Pair<ByteArray, FloatArray>? {
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            null
        } ?: return null
        return try {
            val e = FaceAligner.embedCrop(bmp, landmarker, embedder, detector)
            if (e.method == FaceAligner.MR) null else bytes to e.vec
        } catch (e: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    // Osoba (podľa mena, inak nová) + jej vzory v jednej transakcii; vráti id vzorov v poradí [ready].
    private fun storePerson(
        db: PeopleDatabase,
        dao: PeopleDao,
        name: String,
        ready: List<Pair<ByteArray, FloatArray>>,
    ): List<Long> = db.runInTransaction(
        Callable {
            val now = System.currentTimeMillis()
            val personId = dao.findPersonByName(name)?.id
                ?: dao.insertPerson(PersonEntity(name = name, createdAt = now))
            val sql = db.openHelper.writableDatabase
            ready.map { (_, vec) ->
                val cv = ContentValues().apply {
                    put("person_id", personId)
                    put("embedding", FaceEmbedder.toBytes(vec))
                    put("source", SOURCE)
                    put("created_at", now)
                }
                sql.insert("anchor_embeddings", SQLiteDatabase.CONFLICT_ABORT, cv)
            }
        }
    )

    // výrezy pre budúci prepočet; zlyhanie zápisu import nezastaví (vzor je už uložený)
    private fun saveCrops(context: Context, ids: List<Long>, ready: List<Pair<ByteArray, FloatArray>>) {
        try {
            val dir = File(context.filesDir, CROPS_DIR).apply { mkdirs() }
            for ((i, id) in ids.withIndex()) {
                if (id <= 0) continue
                val target = File(dir, "$id.jpg")
                val part = File(dir, "$id.jpg.part")
                part.writeBytes(ready[i].first)
                if (!part.renameTo(target)) part.delete()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun progressPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)

    private fun finishedFolders(context: Context, hash: String): Set<String> =
        progressPrefs(context).getStringSet("done_$hash", null)?.toSet().orEmpty()

    private fun markFolderFinished(context: Context, hash: String, folder: String) {
        val p = progressPrefs(context)
        val set = HashSet(p.getStringSet("done_$hash", null).orEmpty())
        set.add(folder)
        p.edit().putStringSet("done_$hash", set).commit()
    }

    private fun clearFinishedFolders(context: Context, hash: String) {
        progressPrefs(context).edit().remove("done_$hash").apply()
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
