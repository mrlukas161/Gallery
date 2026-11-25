package org.fossify.gallery.sphere

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.fossify.gallery.spherecore.Mat3
import org.fossify.gallery.spherecore.PanoFrame
import org.fossify.gallery.spherecore.SphereGeo
import kotlin.math.atan2

/** Zdroj natočenia mobilu. Všetky volania prichádzajú na hlavnom vlákne. */
interface OrientationSource {
    fun interface Listener {
        /** `rCw` = kamera → svet (pozri SphereMath.kt), rýchlosť v rad/s, čas v ns. */
        fun onOrientation(rCw: Mat3, angularSpeedRadS: Double, timestampNs: Long)
    }

    /** "game_rotation_vector" | "rotation_vector" | "demo". */
    val sensorName: String

    /** Kompasový azimut optickej osi (0 = sever) podľa posledných údajov, null ak nie je k dispozícii. */
    fun compassHeadingDeg(): Double?

    fun start(listener: Listener)
    fun stop()
}

/** Skutočné senzory: GAME_ROTATION_VECTOR (záloha ROTATION_VECTOR) + gyroskop. */
class SensorOrientationSource(context: Context) : OrientationSource {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gameSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val compassSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val primary: Sensor? = gameSensor ?: compassSensor
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var listener: OrientationSource.Listener? = null

    private val rot9 = FloatArray(9)
    private var gyroSpeed = 0.0
    private var gyroTimeNs = 0L
    private var prevR: Mat3? = null
    private var prevNs = 0L

    @Volatile
    private var compassR: Mat3? = null

    val available: Boolean get() = primary != null

    override val sensorName: String
        get() = if (gameSensor != null) "game_rotation_vector" else "rotation_vector"

    private fun toMat(values: FloatArray): Mat3 {
        // niektoré zariadenia dávajú 5 hodnôt; stačia prvé štyri
        val v = if (values.size > 4) values.copyOf(4) else values
        SensorManager.getRotationMatrixFromVector(rot9, v)
        return SphereGeo.cameraToWorld(Mat3(DoubleArray(9) { rot9[it].toDouble() }))
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val x = e.values[0].toDouble()
                    val y = e.values[1].toDouble()
                    val z = e.values[2].toDouble()
                    gyroSpeed = Math.sqrt(x * x + y * y + z * z)
                    gyroTimeNs = e.timestamp
                }

                Sensor.TYPE_ROTATION_VECTOR -> {
                    val r = toMat(e.values)
                    compassR = r
                    if (primary?.type == Sensor.TYPE_ROTATION_VECTOR) deliver(r, e.timestamp)
                }

                Sensor.TYPE_GAME_ROTATION_VECTOR -> deliver(toMat(e.values), e.timestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun deliver(r: Mat3, ts: Long) {
        // uhlová rýchlosť: gyroskop (ak dáva čerstvé údaje), inak z rozdielu po sebe idúcich natočení
        val p = prevR
        var speed: Double
        if (gyro != null && gyroTimeNs != 0L && ts - gyroTimeNs < 300_000_000L) {
            speed = gyroSpeed
        } else if (p != null && ts > prevNs) {
            val dt = (ts - prevNs) / 1e9
            speed = Math.toRadians((r * p.transpose()).angleDeg()) / dt
        } else {
            speed = 0.0
        }
        prevR = r
        prevNs = ts
        if (speed.isNaN()) speed = 0.0
        listener?.onOrientation(r, speed, ts)
    }

    override fun compassHeadingDeg(): Double? {
        val r = compassR ?: return null
        val axis = SphereGeo.opticalAxis(r)
        if (Math.abs(axis[2]) > 0.95) return null // pohľad takmer zvislo: azimut nemá zmysel
        // svet kompasového vektora: x = východ, y = sever, z = hore
        val deg = Math.toDegrees(atan2(axis[0], axis[1]))
        return (deg + 360.0) % 360.0
    }

    override fun start(listener: OrientationSource.Listener) {
        this.listener = listener
        prevR = null
        gyroTimeNs = 0L
        primary?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        if (gameSensor != null) compassSensor?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyro?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun stop() {
        sm.unregisterListener(sensorListener)
        listener = null
    }
}

/**
 * Ukážkový režim (len ladiaci build): simuluje používateľa, ktorý pomaly (~45 °/s) mieri na každý ďalší
 * nenasnímaný cieľ a stojí, kým sa záber dokončí. `targetPose` vráti požadované natočenie, null = stáť.
 */
class DemoOrientationSource(private val targetPose: () -> Mat3?) : OrientationSource {
    override val sensorName: String = "game_rotation_vector"
    private val handler = Handler(Looper.getMainLooper())
    private val slewer = DemoSlewer(
        PanoFrame.cameraLookingAt(
            PanoFrame(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0)),
            0.0, 0.0
        )
    )
    private var listener: OrientationSource.Listener? = null
    private var lastNs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (lastNs == 0L) 0.0 else (now - lastNs) / 1e9
            lastNs = now
            val speed = slewer.step(targetPose(), dt.coerceAtMost(0.1))
            listener?.onOrientation(slewer.r, speed, now)
            handler.postDelayed(this, 16)
        }
    }

    override fun compassHeadingDeg(): Double? = null

    override fun start(listener: OrientationSource.Listener) {
        this.listener = listener
        running = true
        lastNs = 0L
        handler.post(tick)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
        listener = null
    }
}
