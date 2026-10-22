package org.fossify.gallery.clip

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
import org.fossify.gallery.helpers.BackgroundPriority
import org.fossify.gallery.helpers.HeavyGate
import org.fossify.gallery.helpers.HeavySlot
import java.io.File

// CLIP indexovanie: stiahne modely (ak treba) a pre každú fotku spočíta 512-dim vektor (clip.db).
// Resumovateľné. Vizuál enkodér beží ~50-150 ms/fotka na výkonnom telefóne.
object ClipIndexer {
    @Volatile
    var isRunning = false
        private set

    private const val MAX_DECODE = 512
    private const val CHANNEL_ID = "clip_indexing"
    private const val NOTIF_ID = 49235

    // chyba so zrozumiteľnou správou pre používateľa (bez názvu triedy výnimky)
    private class UserError(message: String) : Exception(message)

    // Vizuálny model sa načíta až pri prvej fotke na spracovanie (s miestom v HeavyGate); keď nie je
    // čo spracovať, nenačíta sa. onDone/onError až po jeho zatvorení.
    fun index(
        context: Context,
        notify: Boolean = true,
        onWaiting: () -> Unit = {},
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        onDone: (indexed: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        val appCtx = context.applicationContext
        ensureBackgroundThread {
            var error: String? = null
            var total = 0
            var done = 0
            BackgroundPriority.run {
                val indexPhase = appCtx.getString(R.string.q1_clip_phase_index)
                val slot = HeavySlot({ isRunning }, onWaiting, onResume = { onProgress(indexPhase, done, total) })
                var encoder: ClipEncoder? = null
                try {
                    if (notify) ensureChannel(appCtx)
                    // 1) modely (stiahnutie pri prvom behu)
                    val ok = ClipModels.ensure(appCtx, { isRunning }) { phase, pct ->
                        onProgress(appCtx.getString(R.string.q1_clip_phase_download, phase), pct, 100)
                        if (notify) notifyProgress(appCtx, appCtx.getString(R.string.q1_notif_clip_download, phase), pct, 100)
                    }
                    if (!isRunning) throw UserError(appCtx.getString(R.string.q1_stopped))
                    if (!ok) throw UserError(appCtx.getString(R.string.q1_clip_download_failed))

                    // 2) indexovanie fotiek
                    val dao = ClipDatabase.getInstance(appCtx).ClipDao()

                    // vylúčené a zamknuté priečinky sa nespracúvajú; ich staré riadky sa z indexu zmažú
                    val scope = org.fossify.gallery.faces.IndexScope(appCtx)
                    val processed = scope.keepAllowed(dao.getIndexedPaths()) { dao.deletePaths(it) }
                    val candidates = queryImages(appCtx).filter { it !in processed && scope.allows(it) }
                    // výsledky z prepojeného telefónu (F4) sa zapíšu hneď, fotka sa druhýkrát nespracúva
                    val todo = FamilyInbox.withoutSupplied(appCtx, candidates, FamilyInbox.CLIP)
                    total = todo.size
                    for (path in todo) {
                        if (!isRunning) break
                        val enc = encoder ?: loadEncoder(appCtx, slot).also { encoder = it }
                        try {
                            val bmp = decode(path)
                            if (bmp != null) {
                                val emb = enc.encodeImage(bmp)
                                bmp.recycle()
                                if (emb != null) {
                                    dao.insert(ClipEntity(path, ClipEncoder.toBytes(emb), System.currentTimeMillis()))
                                }
                            }
                        } catch (ignored: Throwable) {
                        }
                        done++
                        if (done % 5 == 0 || done == total) {
                            onProgress(indexPhase, done, total)
                            if (notify) notifyIndexProgress(appCtx, done, total)
                        }
                    }
                    cancelNotification(appCtx)
                } catch (ignored: HeavyGate.Stopped) {
                    cancelNotification(appCtx)
                } catch (e: Throwable) {
                    cancelNotification(appCtx)
                    error = if (e is UserError) {
                        e.message.orEmpty()
                    } else {
                        e.javaClass.simpleName + (e.message?.let { ": " + it.take(140) } ?: "")
                    }
                } finally {
                    encoder?.close()
                    slot.close()
                    isRunning = false
                }
            }
            val err = error
            if (err != null) onError(err) else onDone(safeCount(appCtx))
        }
    }

    fun stop() {
        isRunning = false
    }

    // vizuálny model až pri prvej fotke, s miestom v HeavyGate (zastavenie počas čakania → HeavyGate.Stopped)
    private fun loadEncoder(context: Context, slot: HeavySlot): ClipEncoder {
        slot.require()
        val encoder = ClipEncoder(context)
        if (!encoder.visualReady()) {
            encoder.close()
            throw UserError(context.getString(R.string.q1_clip_load_failed))
        }
        return encoder
    }

    private fun notifyIndexProgress(context: Context, done: Int, total: Int) =
        notifyProgress(context, context.getString(R.string.q1_notif_clip_index), done, total)

    // aj otvorenie DB v try — callback sa volá mimo hlavného try behu
    private fun safeCount(context: Context) =
        try { ClipDatabase.getInstance(context).ClipDao().count() } catch (e: Throwable) { 0 }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // vždy (aj keď kanál existuje) — systém tak premenuje kanál vytvorený staršou verziou
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.q1_channel_clip), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notifyProgress(context: Context, title: String, done: Int, total: Int) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
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

    private fun queryImages(context: Context): List<String> {
        val list = ArrayList<String>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null,
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { list.add(it) }
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }

    private fun decode(path: String): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > MAX_DECODE || h / sample > MAX_DECODE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
