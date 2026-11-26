package org.fossify.gallery.sphere

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.VelocityTracker
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.databinding.ActivitySphereViewerBinding
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

// Prezeranie jednej guľovej panorámy: ťahanie, štipnutie, gyroskop, ťuk = skryť/zobraziť lištu.
class SphereViewerActivity : SimpleActivity(), SphereRenderer.Listener, SensorEventListener {
    private val binding by viewBinding(ActivitySphereViewerBinding::inflate)
    private lateinit var renderer: SphereRenderer
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var path = ""
    private var meta: SphereViewerMeta? = null
    private var maxTexture = 0
    private var generation = 0
    private var destroyed = false
    private var textureReady = false

    // stav pohľadu (stupne); yaw 0 = stred obrázka
    private var yaw = 0.0
    private var pitch = 0.0
    private var fov = DEFAULT_FOV
    private var defaultYaw = 0.0
    private var defaultFov = DEFAULT_FOV

    // gyroskop
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var gyroOn = false
    private var yawOffset = 0.0
    private var alignTarget: Double? = null
    private var haveRotation = false
    private val rotRaw = FloatArray(9)
    private val rotMapped = FloatArray(9)
    private var sensorRegistered = false

    // dotyk a zotrvačnosť
    private lateinit var gestures: GestureDetector
    private lateinit var scaler: ScaleGestureDetector
    private var tracker: VelocityTracker? = null
    private var scaling = false
    private var vYaw = 0.0
    private var vPitch = 0.0
    private var inertiaRunning = false
    private var lastFrameNs = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    private var uiVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isEmpty()) {
            toast(R.string.sphere_viewer_load_failed)
            finish()
            return
        }
        setContentView(binding.root)
        setupWindow()

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: (if (path.startsWith("content:")) "" else File(path).name)
        binding.sphereTitle.text = title
        binding.sphereBack.setOnClickListener { finish() }
        binding.sphereGyro.setOnClickListener { toggleGyro() }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroOn = rotationSensor != null
        if (BuildConfig.DEBUG) {
            // len ladenie: --es gyro false, --ef yaw 90, --ef pitch 30, --ef fov 60
            if (intent.getStringExtra("gyro") == "false") gyroOn = false
            if (intent.hasExtra("yaw")) yaw = intent.getFloatExtra("yaw", 0f).toDouble()
            if (intent.hasExtra("pitch")) pitch = intent.getFloatExtra("pitch", 0f).toDouble()
            if (intent.hasExtra("fov")) fov = intent.getFloatExtra("fov", DEFAULT_FOV.toFloat()).toDouble()
        }
        updateGyroIcon()

        renderer = SphereRenderer(this)
        binding.sphereGl.apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        setupTouch()
        pushView()

        executor.execute {
            val m = try {
                SphereViewerLoader.readMeta(applicationContext, path)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (destroyed) return@post
                if (m == null) {
                    toast(R.string.sphere_viewer_not_sphere)
                    finish()
                } else {
                    onMeta(m)
                }
            }
        }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val ctl = WindowInsetsControllerCompat(window, binding.root)
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.setOnApplyWindowInsetsListener(binding.sphereRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.sphereBar.setPadding(bars.left + dp(4), bars.top, bars.right + dp(4), 0)
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun onMeta(m: SphereViewerMeta) {
        meta = m
        renderer.setRegion(m.region)
        defaultYaw = m.initialYaw
        defaultFov = m.initialFov
        if (!(BuildConfig.DEBUG && intent.hasExtra("yaw"))) yaw = defaultYaw
        if (!(BuildConfig.DEBUG && intent.hasExtra("fov"))) fov = defaultFov
        if (gyroOn) alignTarget = yaw
        pushView()
        maybeStartDecode()
    }

    // ---- načítanie textúry ----

    override fun onGlReady(maxTextureSize: Int) {
        main.post {
            if (destroyed) return@post
            maxTexture = maxTextureSize
            generation++ // po strate kontextu sa textúra musí nahrať znova
            textureReady = false
            maybeStartDecode()
        }
    }

    private fun maybeStartDecode() {
        val m = meta ?: return
        if (maxTexture <= 0 || textureReady) return
        val gen = ++generation
        binding.sphereProgress.visibility = View.VISIBLE
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bigRam = info.totalMem >= 7_500_000_000L
        executor.execute {
            val bmp = try {
                SphereViewerLoader.decode(applicationContext, path, m, maxTexture, bigRam) { destroyed || gen != generation }
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (destroyed || gen != generation) {
                    bmp?.recycle()
                } else if (bmp == null) {
                    toast(R.string.sphere_viewer_load_failed)
                    finish()
                } else {
                    renderer.setBitmap(bmp)
                    binding.sphereGl.requestRender()
                }
            }
        }
    }

    override fun onTextureUploaded() {
        main.post {
            textureReady = true
            binding.sphereProgress.visibility = View.GONE
            binding.sphereGl.requestRender()
        }
    }

    override fun onTextureFailed() {
        main.post {
            if (destroyed) return@post
            toast(R.string.sphere_viewer_load_failed)
            finish()
        }
    }

    // ---- pohľad ----

    /** Vypočíta (r, u, f) v súradniciach panorámy a pošle ich rendereru. */
    private fun pushView() {
        val r = FloatArray(3)
        val u = FloatArray(3)
        val f = FloatArray(3)
        if (gyroOn && haveRotation) {
            computeGyroBasis(r, u, f)
        } else {
            val sy = sin(Math.toRadians(yaw))
            val cy = cos(Math.toRadians(yaw))
            val sp = sin(Math.toRadians(pitch))
            val cp = cos(Math.toRadians(pitch))
            f[0] = (cp * sy).toFloat(); f[1] = sp.toFloat(); f[2] = (cp * cy).toFloat()
            r[0] = cy.toFloat(); r[1] = 0f; r[2] = (-sy).toFloat()
            u[0] = (-sp * sy).toFloat(); u[1] = cp.toFloat(); u[2] = (-sp * cy).toFloat()
        }
        renderer.setView(r, u, f, fov.toFloat())
        binding.sphereGl.requestRender()
    }

    /**
     * Svet (Z hore, X doprava pri yaw 0, Y dopredu) → panoráma (x, y, z) = (X, Z, Y); R_dw podľa SphereMath:
     * v_svet = R·v_zariadenie. Okno do gule: dopredu = −z obrazovky, doprava = x, hore = y.
     */
    private fun computeGyroBasis(r: FloatArray, u: FloatArray, f: FloatArray) {
        val m = rotMapped
        var fx = -m[2].toDouble(); var fy = -m[8].toDouble(); var fz = -m[5].toDouble() // (x, z, y)
        var rx = m[0].toDouble(); var ry = m[6].toDouble(); var rz = m[3].toDouble()
        var ux = m[1].toDouble(); var uy = m[7].toDouble(); var uz = m[4].toDouble()

        alignTarget?.let { target ->
            // zarovnaj len azimut: aktuálny smer zariadenia → požadovaný azimut
            val raw = when {
                fy > 0.98 -> atan2(-ux, -uz)
                fy < -0.98 -> atan2(ux, uz)
                else -> atan2(fx, fz)
            }
            yawOffset = target - Math.toDegrees(raw)
            alignTarget = null
        }
        val th = Math.toRadians(yawOffset)
        val c = cos(th)
        val s = sin(th)
        fun rot(x: Double, z: Double) = doubleArrayOf(x * c + z * s, -x * s + z * c)
        rot(fx, fz).let { fx = it[0]; fz = it[1] }
        rot(rx, rz).let { rx = it[0]; rz = it[1] }
        rot(ux, uz).let { ux = it[0]; uz = it[1] }
        f[0] = fx.toFloat(); f[1] = fy.toFloat(); f[2] = fz.toFloat()
        r[0] = rx.toFloat(); r[1] = ry.toFloat(); r[2] = rz.toFloat()
        u[0] = ux.toFloat(); u[1] = uy.toFloat(); u[2] = uz.toFloat()
        // zapamätaj si pohľad, aby vypnutie gyra pokračovalo odtiaľto
        pitch = Math.toDegrees(asin(fy.coerceIn(-1.0, 1.0)))
        if (abs(fy) < 0.98) yaw = Math.toDegrees(atan2(fx, fz))
    }

    private fun shortSidePx() = min(binding.sphereGl.width, binding.sphereGl.height).coerceAtLeast(1)

    private fun degPerPx() = fov / shortSidePx()

    private fun clampView() {
        pitch = pitch.coerceIn(-90.0, 90.0)
        fov = fov.coerceIn(MIN_FOV, MAX_FOV)
    }

    private fun resetView() {
        stopInertia()
        fov = defaultFov
        if (gyroOn && haveRotation) {
            alignTarget = defaultYaw
        } else {
            yaw = defaultYaw
            pitch = 0.0
        }
        pushView()
    }

    // ---- ovládanie ----

    private fun setupTouch() {
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaling) return true
                val k = degPerPx()
                // obsah ide za prstom: prst doľava → kamera doprava, prst nadol → kamera hore
                if (gyroOn && haveRotation) {
                    yawOffset += dx * k
                } else {
                    yaw += dx * k
                    pitch -= dy * k
                    clampView()
                }
                pushView()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setUiVisible(!uiVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
        })
        scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaling = true
                stopInertia()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                fov = (fov / d.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
                pushView()
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaling = false
            }
        })
        scaler.isQuickScaleEnabled = false
        binding.sphereGl.setOnTouchListener { _, ev -> onTouch(ev) }
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            stopInertia()
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
        }
        tracker?.addMovement(ev)
        scaler.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val t = tracker
            if (t != null && !scaling) {
                t.computeCurrentVelocity(1000)
                val k = degPerPx()
                startInertia(-t.xVelocity * k, if (gyroOn && haveRotation) 0.0 else t.yVelocity * k)
            }
            tracker?.recycle()
            tracker = null
        } else if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker?.recycle()
            tracker = null
        }
        return true
    }

    private fun startInertia(vy: Double, vp: Double) {
        if (abs(vy) < MIN_INERTIA && abs(vp) < MIN_INERTIA) return
        vYaw = vy
        vPitch = vp
        inertiaRunning = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopInertia() {
        inertiaRunning = false
        vYaw = 0.0
        vPitch = 0.0
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun onFrame(nowNs: Long) {
        if (!inertiaRunning) return
        val dt = if (lastFrameNs == 0L) 0.016 else ((nowNs - lastFrameNs) / 1e9).coerceIn(0.001, 0.05)
        lastFrameNs = nowNs
        if (gyroOn && haveRotation) {
            yawOffset += vYaw * dt
        } else {
            yaw += vYaw * dt
            pitch += vPitch * dt
            clampView()
        }
        val decay = exp(-3.0 * dt)
        vYaw *= decay
        vPitch *= decay
        pushView()
        if (abs(vYaw) < MIN_INERTIA && abs(vPitch) < MIN_INERTIA) {
            stopInertia()
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun setUiVisible(show: Boolean) {
        uiVisible = show
        binding.sphereBar.animate().alpha(if (show) 1f else 0f).setDuration(150)
            .withStartAction { if (show) binding.sphereBar.visibility = View.VISIBLE }
            .withEndAction { if (!show) binding.sphereBar.visibility = View.GONE }
            .start()
        val ctl = WindowInsetsControllerCompat(window, binding.root)
        if (show) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- gyroskop ----

    private fun toggleGyro() {
        if (rotationSensor == null) {
            toast(R.string.sphere_viewer_no_gyro)
            return
        }
        gyroOn = !gyroOn
        stopInertia()
        if (gyroOn) {
            alignTarget = yaw // pokračuj z aktuálneho azimutu
            registerSensor()
        } else {
            unregisterSensor() // yaw a pitch už držia posledný pohľad z gyra
        }
        updateGyroIcon()
        toast(if (gyroOn) R.string.sphere_viewer_gyro_on else R.string.sphere_viewer_gyro_off)
        pushView()
    }

    private fun updateGyroIcon() {
        binding.sphereGyro.alpha = if (gyroOn) 1f else 0.4f
        binding.sphereGyro.visibility = if (rotationSensor == null) View.GONE else View.VISIBLE
    }

    private fun registerSensor() {
        val sm = sensorManager ?: return
        val s = rotationSensor ?: return
        if (sensorRegistered) return
        haveRotation = false
        sensorRegistered = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, main)
    }

    private fun unregisterSensor() {
        if (sensorRegistered) sensorManager?.unregisterListener(this)
        sensorRegistered = false
        haveRotation = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!gyroOn) return
        val v = event.values
        if (v.size < 3 || v.any { it.isNaN() }) return
        try {
            SensorManager.getRotationMatrixFromVector(rotRaw, v)
        } catch (e: Exception) {
            return
        }
        val (ax, ay) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotRaw, ax, ay, rotMapped)) return
        haveRotation = true
        pushView()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) (display?.rotation ?: Surface.ROTATION_0) else windowManager.defaultDisplay.rotation

    // ---- životný cyklus ----

    override fun onResume() {
        super.onResume()
        binding.sphereGl.onResume()
        if (gyroOn) registerSensor()
    }

    override fun onPause() {
        stopInertia()
        unregisterSensor()
        binding.sphereGl.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pushView()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // textúra je už v GPU; uvoľníme len prípadný čakajúci obraz v pamäti procesu
        if (level >= TRIM_MEMORY_BACKGROUND && !textureReady) renderer.releasePending()
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        stopInertia()
        unregisterSensor()
        tracker?.recycle()
        if (::renderer.isInitialized) renderer.releasePending()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        private const val MIN_FOV = 30.0
        private const val MAX_FOV = 110.0
        private const val DEFAULT_FOV = 75.0
        private const val MIN_INERTIA = 2.0
    }
}
