package org.fossify.gallery.sphere

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.R
import org.fossify.gallery.spherecore.GPanoInfo
import org.fossify.gallery.spherecore.PanoStitcher
import org.fossify.gallery.spherecore.SphereSession
import org.fossify.gallery.spherecore.StitchCancelledException
import org.fossify.gallery.spherecore.StitchOptions
import org.fossify.gallery.spherecore.StitchResult
import org.fossify.gallery.spherecore.StitchStage
import org.fossify.gallery.spherecore.XmpGPano
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Skladanie relácie do guľovej fotky na pozadí (foreground služba, typ dataSync). Stav pre obrazovku je
 * v [state]; ten sa číta pravidelne (~300 ms). Zdrojové zábery ostávajú v priečinku relácie.
 */
class SphereStitchService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelFlag = true
                if (!working) {
                    startForegroundCompat(buildProgress("", 0f, false))
                    finish()
                }
            }

            else -> {
                val path = intent?.getStringExtra(EXTRA_DIR)
                startForegroundCompat(buildProgress(StitchStage.LOAD, 0f, true))
                if (path == null) {
                    state = State.Failed("", getString(R.string.sfera_c_err_no_session), false)
                    finish()
                } else if (!working) {
                    working = true
                    cancelFlag = false
                    executor.execute { run(File(path)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun run(dir: File) {
        val lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galeria:sfera")
        wakeLock = lock
        try {
            lock.acquire(60 * 60 * 1000L)
            state = State.Running(dir.absolutePath, StitchStage.LOAD, 0f)
            val (path, uri, result) = stitchAndPublish(dir)
            state = State.Done(dir.absolutePath, path, uri, result.width, result.height, result.warnings)
            try {
                File(dir, SphereStore.MARKER_STITCHED).writeText(path)
            } catch (_: Throwable) {
            }
            notifyDone(path)
        } catch (e: StitchCancelledException) {
            state = State.Failed(dir.absolutePath, getString(R.string.sfera_c_result_cancelled), true)
        } catch (e: Throwable) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            state = State.Failed(dir.absolutePath, msg, cancelFlag)
        } finally {
            try {
                File(dir, SphereStore.TMP_PANO).delete()
            } catch (_: Throwable) {
            }
            if (lock.isHeld) lock.release()
            working = false
            finish()
        }
    }

    private fun stitchAndPublish(dir: File): Triple<String, String, StitchResult> {
        loadOpenCv()
        val session = SphereSession.load(dir)
        val tmp = File(dir, SphereStore.TMP_PANO)
        if (tmp.exists()) tmp.delete()
        var lastNotifyMs = 0L
        var lastStage = ""
        val options = StitchOptions(
            outWidth = outWidthForDevice(),
            onProgress = { stage, fraction ->
                state = State.Running(dir.absolutePath, stage, fraction)
                val now = System.currentTimeMillis()
                if (stage != lastStage || now - lastNotifyMs > 700) {
                    lastStage = stage
                    lastNotifyMs = now
                    notifyProgress(stage, fraction)
                }
            },
            isCancelled = { cancelFlag },
            debugDir = dir,
        )
        val result = PanoStitcher.stitch(session, dir, tmp, options)
        if (cancelFlag) throw StitchCancelledException()

        writeMetadata(tmp, session, result)
        val (path, uri) = publish(tmp)
        return Triple(path, uri, result)
    }

    /** EXIF (čas prvého záberu, výrobca, model) a potom znova XMP GPano, lebo ExifInterface ho môže prepísať. */
    private fun writeMetadata(file: File, session: SphereSession, result: StitchResult) {
        val firstMs = session.frames.firstOrNull()?.timeMs ?: session.startedMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val lastMs = session.frames.lastOrNull()?.timeMs ?: firstMs
        try {
            val exif = ExifInterface(file)
            val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(firstMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Galeria+ Sfera") // EXIF je ASCII, diakritika by sa uložila ako otázniky
            exif.saveAttributes()
        } catch (_: Throwable) {
            // metadáta nie sú podmienkou výsledku
        }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        XmpGPano.write(
            file,
            GPanoInfo(
                fullPanoWidth = result.fullWidth,
                fullPanoHeight = result.fullHeight,
                croppedWidth = result.width,
                croppedHeight = result.height,
                croppedLeft = result.cropLeft,
                croppedTop = result.cropTop,
                poseHeadingDeg = session.headingDeg,
                sourcePhotosCount = result.framesUsed,
                exposureLockUsed = session.exposureLocked,
                firstPhotoDate = iso.format(Date(firstMs)),
                lastPhotoDate = iso.format(Date(lastMs)),
            )
        )
    }

    /** Uloží do `Pictures/Sfera/SFERA_rrrrMMdd_HHmmss.jpg`, vráti absolútnu cestu a content URI. */
    private fun publish(tmp: File): Pair<String, String> {
        val name = "SFERA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sfera")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException(getString(R.string.sfera_c_err_publish))
            try {
                resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException(getString(R.string.sfera_c_err_publish))
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            var path: String? = null
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) path = c.getString(0)
            }
            val abs = path ?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sfera/$name"
            ).absolutePath
            return abs to uri.toString()
        }
        val dirOut = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sfera")
        dirOut.mkdirs()
        val dst = File(dirOut, name)
        tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null)
        return dst.absolutePath to Uri.fromFile(dst).toString()
    }

    private fun outWidthForDevice(): Int {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb >= 10 -> 8192
            gb >= 6 -> 6144
            else -> 4096
        }
    }

    private fun notifyProgress(stage: String, fraction: Float) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildProgress(stage, fraction, true))
        } catch (_: Throwable) {
        }
    }

    private fun buildProgress(stage: String, fraction: Float, showCancel: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SphereCaptureActivity::class.java)
                .putExtra(SphereCaptureActivity.EXTRA_SHOW_RESULT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.sfera_c_notif_title))
            .setContentText(stageText(this, stage))
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
        if (showCancel) {
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, SphereStitchService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, getString(R.string.sfera_c_notif_cancel), cancel)
        }
        return b.build()
    }

    private fun notifyDone(path: String) {
        try {
            val open = PendingIntent.getActivity(
                this, 2, viewerIntent(this, path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.sfera_c_notif_done_title))
                .setContentText(getString(R.string.sfera_c_notif_done_text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_DONE_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun finish() {
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Throwable) {
            try {
                startForeground(NOTIF_ID, n)
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Stav skladania pre obrazovku. */
    sealed class State {
        object Idle : State()
        class Running(val dir: String, val stage: String, val fraction: Float) : State()
        class Done(
            val dir: String, val path: String, val uri: String, val width: Int, val height: Int,
            val warnings: List<String>,
        ) : State()

        class Failed(val dir: String, val message: String, val cancelled: Boolean) : State()
    }

    companion object {
        const val CHANNEL_ID = "galeria_sfera"
        const val EXTRA_DIR = "dir"
        const val ACTION_CANCEL = "org.fossify.gallery.sphere.CANCEL"
        private const val NOTIF_ID = 9120
        private const val NOTIF_DONE_ID = 9121
        const val VIEWER_CLASS = "org.fossify.gallery.sphere.SphereViewerActivity"

        @Volatile
        var state: State = State.Idle

        @Volatile
        private var cancelFlag = false

        @Volatile
        private var openCvLoaded = false

        private fun loadOpenCv() {
            if (openCvLoaded) return
            synchronized(this) {
                if (openCvLoaded) return
                try {
                    System.loadLibrary("opencv_java4")
                } catch (e: UnsatisfiedLinkError) {
                    throw IllegalStateException("OpenCV: " + (e.message ?: "knižnica sa nenačítala"))
                }
                openCvLoaded = true
            }
        }

        /** Spustí skladanie; stav sa hneď prepne na Running (obrazovka tak nevidí starý výsledok). */
        fun start(context: Context, dir: File) {
            if (state is State.Running) return
            cancelFlag = false
            state = State.Running(dir.absolutePath, StitchStage.LOAD, 0f)
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SphereStitchService::class.java).putExtra(EXTRA_DIR, dir.absolutePath)
            )
        }

        fun requestCancel() {
            cancelFlag = true
        }

        /** Zatvorí výsledok (po "Nová guľa"), aby sa nezobrazoval znova. */
        fun reset() {
            if (state !is State.Running) state = State.Idle
        }

        fun viewerIntent(context: Context, path: String): Intent =
            Intent().setClassName(context.applicationContext.packageName, VIEWER_CLASS).putExtra("path", path)

        fun stageText(context: Context, stage: String): String = context.getString(
            when (stage) {
                StitchStage.LOAD -> R.string.sfera_c_stage_load
                StitchStage.MATCH -> R.string.sfera_c_stage_match
                StitchStage.OPTIMIZE -> R.string.sfera_c_stage_optimize
                StitchStage.EXPOSURE -> R.string.sfera_c_stage_exposure
                StitchStage.SEAMS -> R.string.sfera_c_stage_seams
                StitchStage.BLEND -> R.string.sfera_c_stage_blend
                StitchStage.SAVE -> R.string.sfera_c_stage_save
                else -> R.string.sfera_c_stage_prepare
            }
        )

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.sfera_c_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
