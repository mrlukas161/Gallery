package org.fossify.gallery.sphere

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Observer
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.databinding.ActivitySphereCaptureBinding
import org.fossify.gallery.spherecore.Mat3
import org.fossify.gallery.spherecore.PanoFrame
import org.fossify.gallery.spherecore.SphereFrame
import org.fossify.gallery.spherecore.SphereLens
import org.fossify.gallery.spherecore.SphereSession
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Sféra: snímanie guľovej fotky s navádzaním (CameraX + senzor natočenia + [CaptureGuide]) a obrazovka
 * výsledku skladania. Skladanie robí [SphereStitchService], táto aktivita len sleduje jeho stav.
 */
class SphereCaptureActivity : SimpleActivity() {
    private enum class Mode { CAPTURE, RESULT }

    private val binding by viewBinding(ActivitySphereCaptureBinding::inflate)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var mode = Mode.CAPTURE

    // ladiaci ukážkový režim (len DEBUG): am start … --ez demo_sweep true [--ei demo_max N]
    private var demo = false
    private var demoMax = 0

    // kamera
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var baseFov = LensFov.ASSUMED
    private var baseSource = "assumed"
    private var minZoom = 1f
    private var zoomInit = false
    private var wideChosen = true
    private val zoomObserver = Observer<ZoomState> { zs -> if (!zoomInit) onZoomReady(zs) }
    private val streamObserver = Observer<PreviewView.StreamState> { s ->
        if (s == PreviewView.StreamState.STREAMING) onStreaming()
    }

    // navádzanie a relácia
    private var guide = CaptureGuide(LensFov.ASSUMED.hDeg, LensFov.ASSUMED.vDeg)
    private var orientation: OrientationSource? = null
    private var sessionDir: File? = null
    private val frames = ArrayList<SphereFrame>()
    private var lens = SphereLens(1.0, LensFov.ASSUMED.hDeg, LensFov.ASSUMED.vDeg, 0, 0, "assumed")
    private var headingDeg: Double? = null
    private var exposureLocked = false
    private var startedMs = 0L

    @Volatile
    private var curR: Mat3 = Mat3.identity()
    private var busy = false
    private var lastState: GuideState? = null
    private var lastHintKey = ""
    private var cameraStarted = false

    private var sound: MediaActionSound? = null

    // výsledok
    private var resultDir: File? = null
    private var thumbShownFor: String? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onCameraPermitted() else {
            Toast.makeText(this, R.string.sfera_c_perm_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }
    private var pendingStitchDir: File? = null
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStitchDir?.let { runStitch(it) }
        pendingStitchDir = null
    }

    private val orientListener = OrientationSource.Listener { r, speed, _ -> onOrientation(r, speed) }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.RESULT) return
            renderResult()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.sphereTopbar, binding.sphereResultContent),
            padBottomSystem = listOf(binding.sphereBottombar)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo_sweep", false)
        demoMax = if (demo) intent.getIntExtra("demo_max", 0) else 0
        binding.sphereGuide.setGuide(guide)
        setupClicks()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onClosePressed()
        })

        val running = SphereStitchService.state
        if (intent.getBooleanExtra(EXTRA_SHOW_RESULT, false) && running !is SphereStitchService.State.Idle) {
            showResult()
            return
        }
        showCaptureViews()
        updateUi()
        ensureCameraPermission()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_RESULT, false) && SphereStitchService.state !is SphereStitchService.State.Idle) {
            showResult()
        }
    }

    override fun onStart() {
        super.onStart()
        if (mode == Mode.CAPTURE && cameraStarted) startOrientation()
        else if (mode == Mode.RESULT) handler.post(pollRunnable)
    }

    override fun onStop() {
        orientation?.stop()
        handler.removeCallbacks(pollRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        orientation?.stop()
        sound?.release()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ovládanie

    private fun setupClicks() {
        binding.sphereClose.setOnClickListener { onClosePressed() }
        binding.sphereShutter.setOnClickListener { onShutter() }
        binding.sphereUndo.setOnClickListener { onUndo() }
        binding.sphereDone.setOnClickListener { onDonePressed() }
        binding.sphereMore.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(R.string.sfera_c_btn_sources)
                setOnMenuItemClickListener { showSourcesDialog(); true }
            }.show()
        }
        binding.sphereLensWide.setOnClickListener { if (frames.isEmpty()) applyLens(true) }
        binding.sphereLensMain.setOnClickListener { if (frames.isEmpty()) applyLens(false) }
        binding.sphereLockExposure.setOnCheckedChangeListener { _, _ -> /* platí od prvého záberu */ }
        binding.sphereTipsOk.setOnClickListener {
            binding.sphereTips.visibility = View.GONE
            prefs().edit().putBoolean(PREF_TIPS, true).apply()
        }
        binding.sphereBtnView.setOnClickListener { openViewer() }
        binding.sphereBtnNew.setOnClickListener { startNewSession() }
        binding.sphereBtnZip.setOnClickListener { exportZip() }
        binding.sphereBtnRetry.setOnClickListener { retryStitch() }
        binding.sphereBtnCancel.setOnClickListener { SphereStitchService.requestCancel() }
        binding.sphereBtnSources.setOnClickListener { showSourcesDialog() }
        binding.sphereBtnClose.setOnClickListener { finish() }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun showCaptureViews() {
        mode = Mode.CAPTURE
        handler.removeCallbacks(pollRunnable)
        binding.sphereResult.visibility = View.GONE
        for (v in listOf(binding.spherePreview, binding.sphereGuide, binding.sphereTopbar, binding.sphereBottombar)) {
            v.visibility = View.VISIBLE
        }
        if (!prefs().getBoolean(PREF_TIPS, false) && !demo) binding.sphereTips.visibility = View.VISIBLE
    }

    private fun onClosePressed() {
        if (mode == Mode.RESULT) {
            finish()
            return
        }
        if (frames.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sfera_c_discard_title)
                .setMessage(getString(R.string.sfera_c_discard_message, frames.size))
                .setPositiveButton(R.string.sfera_c_discard_yes) { _, _ ->
                    sessionDir?.let { d -> worker.execute { SphereStore.delete(d) } }
                    finish()
                }
                .setNegativeButton(R.string.sfera_c_discard_no, null)
                .show()
        } else {
            sessionDir?.let { SphereStore.delete(it) }
            finish()
        }
    }

    private fun onShutter() {
        if (busy || mode != Mode.CAPTURE || imageCapture == null) return
        binding.sphereTips.visibility = View.GONE
        if (!guide.hasFirstFrame) {
            if (lastState?.firstFrameReady != true) {
                Toast.makeText(this, R.string.sfera_c_first_not_ready, Toast.LENGTH_SHORT).show()
                return
            }
            capture(0)
        } else {
            capture(-1) // ručný záber mimo cieľov
        }
    }

    private fun onUndo() {
        if (busy || frames.isEmpty()) return
        guide.undoLast() ?: return
        val f = frames.removeAt(frames.size - 1)
        val d = sessionDir
        if (d != null) File(d, f.file).delete()
        binding.sphereGuide.removeThumb(f.file)
        if (frames.isEmpty()) {
            unlockExposure()
            headingDeg = null
            sessionDir?.let { SphereStore.delete(it) }
            sessionDir = null
        } else {
            saveSession(false)
        }
        updateUi()
    }

    private fun onDonePressed() {
        if (busy || frames.size < 2) return
        if (!guide.horizonComplete && !guide.isComplete) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sfera_c_partial_title)
                .setMessage(R.string.sfera_c_partial_message)
                .setPositiveButton(R.string.sfera_c_partial_yes) { _, _ -> finishSession() }
                .setNegativeButton(R.string.sfera_c_partial_no, null)
                .show()
        } else {
            finishSession()
        }
    }

    // ---------------------------------------------------------------- povolenia a štart kamery

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onCameraPermitted()
            return
        }
        if (demo) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sfera_c_perm_title)
            .setMessage(R.string.sfera_c_perm_message)
            .setCancelable(false)
            .setPositiveButton(R.string.sfera_c_perm_ok) { _, _ -> cameraPermission.launch(Manifest.permission.CAMERA) }
            .setNegativeButton(R.string.sfera_c_perm_cancel) { _, _ -> finish() }
            .show()
    }

    private fun onCameraPermitted() {
        if (orientation == null) {
            val o = if (demo) DemoOrientationSource { demoPose() } else SensorOrientationSource(this)
            if (o is SensorOrientationSource && !o.available) {
                Toast.makeText(this, R.string.sfera_c_no_sensor, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            orientation = o
        }
        cameraStarted = true
        startCamera()
        startOrientation()
        if (!demo) offerResume()
    }

    private fun startOrientation() {
        orientation?.start(orientListener)
    }

    private fun offerResume() {
        if (frames.isNotEmpty() || sessionDir != null) return
        worker.execute {
            val found = SphereStore.findResumable(this)
            if (found != null) runOnUiThread {
                if (isFinishing || isDestroyed || frames.isNotEmpty()) return@runOnUiThread
                val (dir, session) = found
                AlertDialog.Builder(this)
                    .setTitle(R.string.sfera_c_resume_title)
                    .setMessage(R.string.sfera_c_resume_message)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.sfera_c_resume_stitch, session.frames.size)) { _, _ ->
                        worker.execute {
                            try {
                                session.copy(finished = true).save(dir)
                            } catch (_: Throwable) {
                            }
                            runOnUiThread { startStitching(dir) }
                        }
                    }
                    .setNegativeButton(R.string.sfera_c_resume_discard) { _, _ -> worker.execute { SphereStore.delete(dir) } }
                    .setNeutralButton(R.string.sfera_c_resume_new, null)
                    .show()
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bindCamera()
            } catch (e: Throwable) {
                Toast.makeText(this, R.string.sfera_c_camera_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        if (isDestroyed) return
        p.unbindAll()
        binding.spherePreview.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.spherePreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val pv = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            )
            .build()
        pv.surfaceProvider = binding.spherePreview.surfaceProvider
        val ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .build()
        preview = pv
        imageCapture = ic
        val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
        camera = cam
        zoomInit = false
        cam.cameraInfo.zoomState.removeObserver(zoomObserver)
        cam.cameraInfo.zoomState.observe(this, zoomObserver)
        binding.spherePreview.previewStreamState.removeObserver(streamObserver)
        binding.spherePreview.previewStreamState.observe(this, streamObserver)
    }

    private fun unbindCamera() {
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        binding.spherePreview.previewStreamState.removeObserver(streamObserver)
        provider?.unbindAll()
        camera = null
        imageCapture = null
    }

    /** Skutočný pomer strán zobrazeného obrazu (na výšku). */
    private fun onStreaming() {
        val res = preview?.resolutionInfo?.resolution ?: return
        val shortSide = min(res.width, res.height).toDouble()
        val longSide = max(res.width, res.height).toDouble()
        if (longSide > 0) binding.sphereGuide.setImageAspect(shortSide / longSide)
    }

    private fun onZoomReady(zs: ZoomState) {
        zoomInit = true
        minZoom = zs.minZoomRatio
        readBaseFov()
        val hasWide = minZoom < 0.95f
        binding.sphereLensWide.visibility = if (hasWide) View.VISIBLE else View.GONE
        binding.sphereLensMain.visibility = if (hasWide) View.VISIBLE else View.GONE
        if (frames.isEmpty()) {
            wideChosen = hasWide
            applyLens(hasWide)
        } else {
            // po návrate do popredia: objektív ostáva, zoom sa len obnoví
            camera?.cameraControl?.setZoomRatio(lens.zoomRatio.toFloat())
            if (exposureLocked) setAeAwbLock(true)
        }
        if (demo) handler.postDelayed({ demoFirstShot() }, 1500)
    }

    /** Zorné pole hlavného objektívu z charakteristík kamery; záloha = odhad 24 mm ekvivalentu. */
    private fun readBaseFov() {
        var fov = LensFov.ASSUMED
        var src = "assumed"
        try {
            val info = Camera2CameraInfo.from(camera!!.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pix = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val act = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (focal != null && size != null) {
                var w = size.width.toDouble()
                var h = size.height.toDouble()
                if (pix != null && act != null && pix.width > 0 && pix.height > 0) {
                    w *= act.width().toDouble() / pix.width
                    h *= act.height().toDouble() / pix.height
                }
                LensFov.fromSensor(focal.toDouble(), w, h)?.let {
                    fov = it
                    src = "characteristics"
                }
            }
        } catch (_: Throwable) {
        }
        baseFov = fov
        baseSource = src
    }

    private fun applyLens(wide: Boolean) {
        wideChosen = wide
        val zoom = if (wide && minZoom < 0.95f) minZoom else 1f
        camera?.cameraControl?.setZoomRatio(zoom)
        val fov = LensFov.withZoom(baseFov, zoom.toDouble())
        guide.setFov(fov.hDeg, fov.vDeg)
        binding.sphereGuide.setFov(fov.hDeg, fov.vDeg)
        lens = SphereLens(zoom.toDouble(), fov.hDeg, fov.vDeg, 0, 0, baseSource)
        updateUi()
    }

    // ---------------------------------------------------------------- snímanie

    private fun onOrientation(r: Mat3, speed: Double) {
        curR = r
        val st = guide.update(r, speed, SystemClock.elapsedRealtime())
        lastState = st
        binding.sphereGuide.update(r, st)
        updateHint(st)
        if (st.shouldCapture && !busy && mode == Mode.CAPTURE) capture(st.nearest)
    }

    private fun capture(target: Int) {
        val ic = imageCapture ?: run { guide.abortCapture(); return }
        if (busy) return
        busy = true
        val dir = ensureSessionDir()
        val file = File(dir, "f_%03d.jpg".format(frames.size))
        var shutterR = curR
        guide.onCaptureStarted(shutterR)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                // okamih spúšte: natočenie sa uloží k záberu
                shutterR = curR
                guide.onCaptureStarted(shutterR)
                feedback()
            }

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onFrameSaved(file, target, shutterR)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                guide.abortCapture()
                busy = false
                Toast.makeText(this@SphereCaptureActivity, R.string.sfera_c_save_failed, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun feedback() {
        try {
            binding.sphereGuide.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (sound == null) sound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
            sound?.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
    }

    private fun onFrameSaved(file: File, target: Int, shutterR: Mat3) {
        val moved = guide.onCaptureFinished(curR)
        if (moved > CaptureGuide.MAX_MOVE_DEG) {
            file.delete()
            busy = false
            Toast.makeText(this, R.string.sfera_c_moved, Toast.LENGTH_SHORT).show()
            return
        }
        val first = !guide.hasFirstFrame
        var frameTarget = target
        if (first) {
            val (w, h) = readImageSize(file)
            lens = lens.copy(imageWidth = w, imageHeight = h)
            headingDeg = orientation?.compassHeadingDeg()
            startedMs = System.currentTimeMillis()
            guide.setFirstFrame(shutterR)
            frameTarget = 0
            lockExposure()
        } else {
            guide.markCaptured(target)
        }
        frames.add(SphereFrame(file.name, frameTarget, System.currentTimeMillis(), shutterR, moved))
        saveSession(false)
        addThumb(file, shutterR)
        busy = false
        updateUi()
        if (guide.isComplete || (demoMax > 0 && frames.size >= demoMax)) finishSession()
    }

    private fun addThumb(file: File, r: Mat3) {
        worker.execute {
            val bmp = decodeThumb(file) ?: return@execute
            runOnUiThread {
                if (!isDestroyed && frames.any { it.file == file.name }) binding.sphereGuide.addThumb(file.name, bmp, r)
            }
        }
    }

    private fun ensureSessionDir(): File {
        sessionDir?.let { return it }
        val d = SphereStore.newSessionDir(this)
        sessionDir = d
        return d
    }

    private fun saveSession(finished: Boolean) {
        val d = sessionDir ?: return
        try {
            SphereSession(
                id = d.name,
                device = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
                lens = lens,
                sensor = orientation?.sensorName ?: "",
                headingDeg = headingDeg,
                exposureLocked = exposureLocked,
                frames = frames.toList(),
                startedMs = startedMs,
                finished = finished,
            ).save(d)
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.sfera_c_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun lockExposure() {
        if (!binding.sphereLockExposure.isChecked) return
        exposureLocked = setAeAwbLock(true)
    }

    private fun unlockExposure() {
        if (!exposureLocked) return
        try {
            camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
        } catch (_: Throwable) {
        }
        exposureLocked = false
    }

    private fun setAeAwbLock(lock: Boolean): Boolean {
        val c = camera ?: return false
        return try {
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                    .build()
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readImageSize(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var w = o.outWidth
        var h = o.outHeight
        val rot = try {
            ExifInterface(f.path).rotationDegrees
        } catch (_: Throwable) {
            0
        }
        if (rot == 90 || rot == 270) {
            val t = w
            w = h
            h = t
        }
        return w to h
    }

    /** Zmenšenina ~256 px (dlhšia strana) otočená podľa EXIF. */
    private fun decodeThumb(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) null else {
            val rot = try {
                ExifInterface(f.path).rotationDegrees
            } catch (_: Throwable) {
                0
            }
            val k = 256f / max(bmp.width, bmp.height)
            val m = Matrix().apply { postRotate(rot.toFloat()); postScale(k, k) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------- stav obrazovky

    private fun updateUi() {
        val started = frames.isNotEmpty()
        binding.sphereCounter.text = getString(R.string.sfera_c_counter, guide.capturedCount, guide.total)
        binding.sphereUndo.alpha = if (started) 1f else 0.35f
        binding.sphereDone.alpha = if (frames.size >= 2) 1f else 0.35f
        binding.sphereMore.visibility = if (started) View.GONE else View.VISIBLE
        binding.sphereLockExposure.isEnabled = !started
        val wideSelected = wideChosen && minZoom < 0.95f
        styleChip(binding.sphereLensWide, wideSelected, !started)
        styleChip(binding.sphereLensMain, !wideSelected, !started)
    }

    private fun styleChip(v: android.widget.TextView, selected: Boolean, enabled: Boolean) {
        v.isSelected = selected
        v.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        v.alpha = if (enabled) 1f else 0.6f
    }

    private fun updateHint(st: GuideState) {
        val tv = binding.sphereHint
        val res: Int? = when {
            guide.isComplete -> null
            !st.hasFirstFrame -> when (st.hint) {
                GuideHint.AIM_HORIZON -> R.string.sfera_c_hint_aim_horizon
                GuideHint.SLOWER -> R.string.sfera_c_hint_slower
                else -> R.string.sfera_c_hint_first
            }

            else -> when (st.hint) {
                GuideHint.SLOWER -> R.string.sfera_c_hint_slower
                GuideHint.LEVEL -> R.string.sfera_c_hint_level
                GuideHint.HOLD -> R.string.sfera_c_hint_hold
                GuideHint.MOVE_TO_TARGET -> if (st.distanceDeg < 25.0) R.string.sfera_c_hint_move else null
                else -> null
            }
        }
        val key = res?.toString() ?: ""
        if (key == lastHintKey) return
        lastHintKey = key
        if (res == null) tv.visibility = View.INVISIBLE else {
            tv.setText(res)
            tv.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------- ukážkový režim

    private fun demoPose(): Mat3? {
        if (busy || mode != Mode.CAPTURE) return null
        val pf = guide.panoFrame ?: return null
        val i = guide.remaining.firstOrNull() ?: return null
        val t = guide.targets[i]
        return PanoFrame.cameraLookingAt(pf, t.yawDeg, t.pitchDeg)
    }

    private fun demoFirstShot() {
        if (isDestroyed || mode != Mode.CAPTURE || guide.hasFirstFrame) return
        if (!busy && lastState?.firstFrameReady == true && imageCapture != null) onShutter()
        else handler.postDelayed({ demoFirstShot() }, 300)
    }

    // ---------------------------------------------------------------- skladanie a výsledok

    private fun finishSession() {
        if (frames.size < 2 || mode != Mode.CAPTURE) return
        val d = sessionDir ?: return
        orientation?.stop()
        saveSession(true)
        startStitching(d)
    }

    private fun startStitching(d: File) {
        val needNotif = Build.VERSION.SDK_INT >= 33 && !demo &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs().getBoolean(PREF_NOTIF_ASKED, false)
        if (needNotif) {
            prefs().edit().putBoolean(PREF_NOTIF_ASKED, true).apply()
            pendingStitchDir = d
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runStitch(d)
        }
    }

    private fun runStitch(d: File) {
        SphereStitchService.start(this, d)
        resultDir = d
        showResult()
    }

    private fun retryStitch() {
        val d = resultDir ?: return
        SphereStitchService.start(this, d)
    }

    private fun showResult() {
        mode = Mode.RESULT
        orientation?.stop()
        unbindCamera()
        for (v in listOf(binding.spherePreview, binding.sphereGuide, binding.sphereTopbar, binding.sphereBottombar, binding.sphereTips)) {
            v.visibility = View.GONE
        }
        binding.sphereHint.visibility = View.INVISIBLE
        lastHintKey = ""
        binding.sphereResult.visibility = View.VISIBLE
        thumbShownFor = null
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun renderResult() {
        val b = binding
        val st = SphereStitchService.state
        fun show(vararg v: View) {
            for (x in listOf(b.sphereBtnView, b.sphereBtnRetry, b.sphereBtnNew, b.sphereBtnZip, b.sphereBtnSources, b.sphereBtnCancel, b.sphereBtnClose)) {
                x.visibility = if (x in v) View.VISIBLE else View.GONE
            }
        }
        when (st) {
            is SphereStitchService.State.Running -> {
                resultDir = File(st.dir)
                b.sphereResultTitle.setText(R.string.sfera_c_result_running)
                b.sphereResultProgress.visibility = View.VISIBLE
                b.sphereResultProgress.progress = (st.fraction * 1000).toInt().coerceIn(0, 1000)
                b.sphereResultStage.visibility = View.VISIBLE
                b.sphereResultStage.text = SphereStitchService.stageText(this, st.stage)
                b.sphereResultNote.visibility = View.VISIBLE
                b.sphereResultError.visibility = View.GONE
                b.sphereResultImage.visibility = View.GONE
                show(b.sphereBtnCancel, b.sphereBtnClose)
            }

            is SphereStitchService.State.Done -> {
                resultDir = File(st.dir)
                b.sphereResultTitle.setText(R.string.sfera_c_result_done)
                b.sphereResultProgress.visibility = View.GONE
                b.sphereResultStage.visibility = View.GONE
                b.sphereResultNote.visibility = View.GONE
                if (st.warnings.isNotEmpty()) {
                    b.sphereResultError.visibility = View.VISIBLE
                    b.sphereResultError.setTextColor(Color.parseColor("#B3FFFFFF"))
                    b.sphereResultError.text = getString(R.string.sfera_c_warnings, st.warnings.joinToString("; "))
                } else b.sphereResultError.visibility = View.GONE
                if (thumbShownFor != st.path) {
                    thumbShownFor = st.path
                    loadResultThumb(st.path)
                }
                show(b.sphereBtnView, b.sphereBtnNew, b.sphereBtnZip, b.sphereBtnSources, b.sphereBtnClose)
            }

            is SphereStitchService.State.Failed -> {
                if (st.dir.isNotEmpty()) resultDir = File(st.dir)
                b.sphereResultTitle.setText(if (st.cancelled) R.string.sfera_c_result_cancelled else R.string.sfera_c_result_failed)
                b.sphereResultProgress.visibility = View.GONE
                b.sphereResultStage.visibility = View.GONE
                b.sphereResultNote.visibility = View.GONE
                b.sphereResultImage.visibility = View.GONE
                b.sphereResultError.visibility = if (st.cancelled) View.GONE else View.VISIBLE
                b.sphereResultError.setTextColor(Color.parseColor("#FFFF8A80"))
                b.sphereResultError.text = st.message
                show(b.sphereBtnRetry, b.sphereBtnNew, b.sphereBtnZip, b.sphereBtnSources, b.sphereBtnClose)
            }

            else -> {
                b.sphereResultTitle.text = ""
                b.sphereResultProgress.visibility = View.GONE
                b.sphereResultStage.visibility = View.GONE
                b.sphereResultNote.visibility = View.GONE
                b.sphereResultError.visibility = View.GONE
                b.sphereResultImage.visibility = View.GONE
                show(b.sphereBtnNew, b.sphereBtnSources, b.sphereBtnClose)
            }
        }
    }

    private fun loadResultThumb(path: String) {
        worker.execute {
            val bmp = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 1200) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (!isDestroyed && bmp != null && mode == Mode.RESULT) {
                    binding.sphereResultImage.setImageBitmap(bmp)
                    binding.sphereResultImage.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openViewer() {
        val st = SphereStitchService.state as? SphereStitchService.State.Done ?: return
        try {
            startActivity(SphereStitchService.viewerIntent(this, st.path))
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.sfera_c_viewer_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportZip() {
        val d = resultDir ?: return
        Toast.makeText(this, R.string.sfera_c_btn_zip, Toast.LENGTH_SHORT).show()
        worker.execute {
            val name = SphereStore.exportZip(this, d)
            runOnUiThread {
                if (name != null) Toast.makeText(this, getString(R.string.sfera_c_zip_saved, name), Toast.LENGTH_LONG).show()
                else Toast.makeText(this, R.string.sfera_c_zip_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Nová relácia po skončení predchádzajúcej: čistý stav a znova kamera. */
    private fun startNewSession() {
        SphereStitchService.reset()
        guide = CaptureGuide(baseFov.hDeg, baseFov.vDeg)
        binding.sphereGuide.setGuide(guide)
        binding.sphereGuide.clearThumbs()
        frames.clear()
        sessionDir = null
        resultDir = null
        headingDeg = null
        exposureLocked = false
        busy = false
        lastState = null
        thumbShownFor = null
        showCaptureViews()
        updateUi()
        if (cameraStarted && provider != null) {
            bindCamera()
            startOrientation()
        } else {
            ensureCameraPermission()
        }
    }

    // ---------------------------------------------------------------- zdrojové zábery

    private fun showSourcesDialog() {
        worker.execute {
            val dirs = SphereStore.list(this)
            val size = dirs.sumOf { SphereStore.sizeOf(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val b = AlertDialog.Builder(this).setTitle(R.string.sfera_c_sources_title)
                if (dirs.isEmpty()) {
                    b.setMessage(R.string.sfera_c_sources_empty)
                } else {
                    b.setMessage(getString(R.string.sfera_c_sources_message, dirs.size, size.formatSize()))
                    b.setPositiveButton(R.string.sfera_c_sources_delete_all) { _, _ -> confirmDeleteSources(size) }
                }
                b.setNegativeButton(R.string.sfera_c_sources_close, null).show()
            }
        }
    }

    private fun confirmDeleteSources(size: Long) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sfera_c_sources_confirm, size.formatSize()))
            .setPositiveButton(R.string.sfera_c_yes) { _, _ ->
                val keep = HashSet<String>()
                sessionDir?.let { keep.add(it.absolutePath) }
                (SphereStitchService.state as? SphereStitchService.State.Running)?.let { keep.add(File(it.dir).absolutePath) }
                worker.execute {
                    SphereStore.deleteAll(this, keep)
                    runOnUiThread { Toast.makeText(this, R.string.sfera_c_sources_deleted, Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(R.string.sfera_c_no, null)
            .show()
    }

    companion object {
        const val EXTRA_SHOW_RESULT = "show_result"
        private const val PREFS = "galeria_sfera"
        private const val PREF_TIPS = "tips_shown"
        private const val PREF_NOTIF_ASKED = "notif_asked"
    }
}
