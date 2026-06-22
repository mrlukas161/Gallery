package org.fossify.gallery.faces

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// Import "vzorov" tvárí z Picasy: ZIP (manifest.json + person_xx/crop_*.jpg).
// Pre každý výrez: detekuj BlazeFace -> embedni MobileFaceNet -> ulož ako anchor embedding osoby.
// Tým vzniknú embeddingy v ROVNAKOM priestore ako telefónne tváre. Jednorazový beh.
object PicasaImportService {
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
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var detector: FaceDetectionHelper? = null
            var embedder: FaceEmbedder? = null
            var tmp: File? = null
            try {
                // 1) skopíruj ZIP do cache (ZipFile potrebuje náhodný prístup k súboru)
                tmp = File(appCtx.cacheDir, "picasa_import_${System.currentTimeMillis()}.zip")
                appCtx.contentResolver.openInputStream(zipUri)?.use { input ->
                    tmp!!.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Nepodarilo sa otvoriť ZIP")

                val dao = PeopleDatabase.getInstance(appCtx).PeopleDao()
                val zip = ZipFile(tmp)
                try {
                    val manifestEntry = zip.getEntry("manifest.json")
                        ?: throw IllegalStateException("V ZIP-e chýba manifest.json")
                    val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
                    val hash = sha256(manifestBytes)

                    if (dao.findImportManifest(hash) != null) {
                        onDone(0, 0, 0, true)
                        return@ensureBackgroundThread
                    }

                    val root = JSONObject(String(manifestBytes, Charsets.UTF_8))
                    val people = root.getJSONArray("people")
                    var totalCrops = 0
                    for (i in 0 until people.length()) {
                        totalCrops += people.getJSONObject(i).optJSONArray("crops")?.length() ?: 0
                    }

                    detector = FaceDetectionHelper(appCtx)
                    embedder = FaceEmbedder(appCtx)
                    val now = System.currentTimeMillis()
                    var done = 0
                    var anchors = 0
                    var skipped = 0
                    var personsTouched = 0

                    for (i in 0 until people.length()) {
                        if (!isRunning) break
                        val po = people.getJSONObject(i)
                        val name = po.optString("name").trim()
                        val folder = po.optString("folder")
                        if (name.isEmpty() || folder.isEmpty()) continue
                        val crops = po.optJSONArray("crops") ?: continue

                        val personId = dao.findPersonByName(name)?.id
                            ?: dao.insertPerson(PersonEntity(name = name, createdAt = now))
                        personsTouched++

                        for (j in 0 until crops.length()) {
                            if (!isRunning) break
                            val cropName = crops.optString(j)
                            val entry = zip.getEntry("$folder/$cropName")
                            if (entry != null) {
                                try {
                                    val bytes = zip.getInputStream(entry).readBytes()
                                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bmp != null) {
                                        val best = detector!!.detect(bmp).maxByOrNull { it.score }
                                        if (best != null) {
                                            val emb = embedder!!.embed(bmp, best)
                                            dao.insertAnchor(
                                                AnchorEmbeddingEntity(
                                                    personId = personId,
                                                    embedding = FaceEmbedder.toBytes(emb),
                                                    source = "picasa",
                                                    createdAt = now,
                                                )
                                            )
                                            anchors++
                                        } else {
                                            skipped++
                                        }
                                        bmp.recycle()
                                    } else {
                                        skipped++
                                    }
                                } catch (e: Throwable) {
                                    skipped++
                                }
                            }
                            done++
                            if (done % 5 == 0 || done == totalCrops) onProgress(done, totalCrops)
                        }
                    }

                    dao.insertImportManifest(ImportManifestEntity(hash, now, personsTouched))
                    onDone(personsTouched, anchors, skipped, false)
                } finally {
                    try {
                        zip.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (e: Throwable) {
                onError(e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: ""))
            } finally {
                detector?.close()
                embedder?.close()
                try {
                    tmp?.delete()
                } catch (ignored: Throwable) {
                }
                isRunning = false
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
