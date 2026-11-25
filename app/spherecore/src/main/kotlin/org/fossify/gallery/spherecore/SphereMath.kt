package org.fossify.gallery.spherecore

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometria režimu Sféra. ZÁVÄZNÉ DOHODY (platia pre snímanie, skladanie aj prezeranie):
 *
 * SVET (W): os Z hore (proti gravitácii), X a Y vodorovne. Azimut je ľubovoľný
 *   (senzor GAME_ROTATION_VECTOR nemá kompas), pevný počas jednej relácie.
 * ZARIADENIE (D), ako ho dáva Android: x doprava, y hore (k hornej hrane mobilu na výšku),
 *   z von z displeja k používateľovi. SensorManager.getRotationMatrixFromVector dáva R_dw:
 *   v_svet = R_dw · v_zariadenie.
 * KAMERA (C) = obraz JPEG otočený podľa EXIF (mobil na výšku): x doprava, y DOLE, z dopredu do scény.
 *   v_zariadenie = (x_c, −y_c, −z_c), teda R_cw = R_dw · diag(1, −1, −1).
 *   V relácii sa ukladá R_cw (kamera → svet), 9 čísel po riadkoch.
 * Dierková kamera: pixel (px, py) → lúč v kamere ((px − cx)/f, (py − cy)/f, 1), cx = šírka/2, cy = výška/2.
 * PANORÁMA: pozri [PanoFrame]. Stĺpec u ∈ [0, W) ↔ azimut lon = (u/W − 0,5)·360° (stred = smer prvého
 *   záberu, doprava rastie = otáčanie doprava), riadok v ∈ [0, H) ↔ výška lat = (0,5 − v/H)·180°.
 */
class Mat3(val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Mat3(r)
    }

    fun mul(v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /** Mᵀ · v (pre rotácie = inverzná rotácia). */
    fun mulT(v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
        m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
        m[2] * v[0] + m[5] * v[1] + m[8] * v[2],
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    /** Stĺpec c ako vektor (obraz jednotkovej osi). */
    fun col(c: Int) = doubleArrayOf(m[c], m[3 + c], m[6 + c])

    /** Uhol rotácie v stupňoch (0 = identita). */
    fun angleDeg(): Double {
        val t = ((m[0] + m[4] + m[8]) - 1.0) / 2.0
        return Math.toDegrees(acos(t.coerceIn(-1.0, 1.0)))
    }

    /** Logaritmická mapa: rotácia → vektor os·uhol (radiány). */
    fun log(): DoubleArray {
        val c = (((m[0] + m[4] + m[8]) - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        val a = acos(c)
        val v = doubleArrayOf(m[7] - m[5], m[2] - m[6], m[3] - m[1])
        if (a < 1e-9) return doubleArrayOf(v[0] / 2, v[1] / 2, v[2] / 2)
        if (Math.PI - a < 1e-6) {
            // blízko 180°: os z diagonály
            val x = sqrt(((m[0] + 1) / 2).coerceAtLeast(0.0))
            val y = sqrt(((m[4] + 1) / 2).coerceAtLeast(0.0))
            val z = sqrt(((m[8] + 1) / 2).coerceAtLeast(0.0))
            val sy = if (m[1] + m[3] < 0) -1.0 else 1.0
            val sz = if (m[2] + m[6] < 0) -1.0 else 1.0
            return doubleArrayOf(x * a, sy * y * a, sz * z * a)
        }
        val k = a / (2 * sin(a))
        return doubleArrayOf(v[0] * k, v[1] * k, v[2] * k)
    }

    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun diag(a: Double, b: Double, c: Double) = Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        /** Stĺpce = dané vektory. */
        fun fromCols(x: DoubleArray, y: DoubleArray, z: DoubleArray) =
            Mat3(doubleArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]))

        /** Exponenciálna mapa (Rodrigues): vektor os·uhol (radiány) → rotácia. */
        fun exp(w: DoubleArray): Mat3 {
            val a = Vec.norm(w)
            if (a < 1e-12) return identity()
            val x = w[0] / a
            val y = w[1] / a
            val z = w[2] / a
            val c = cos(a)
            val s = sin(a)
            val t = 1 - c
            return Mat3(
                doubleArrayOf(
                    t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                    t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                    t * x * z - s * y, t * y * z + s * x, t * z * z + c,
                )
            )
        }

        fun axisAngleDeg(axis: DoubleArray, deg: Double): Mat3 {
            val n = Vec.normalize(axis)
            val a = Math.toRadians(deg)
            return exp(doubleArrayOf(n[0] * a, n[1] * a, n[2] * a))
        }
    }
}

object Vec {
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun norm(a: DoubleArray) = sqrt(dot(a, a))

    fun normalize(a: DoubleArray): DoubleArray {
        val n = norm(a)
        return if (n < 1e-15) doubleArrayOf(0.0, 0.0, 0.0) else doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val c = dot(a, b) / (norm(a) * norm(b)).coerceAtLeast(1e-15)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    fun scale(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
}

object SphereGeo {
    val UP = doubleArrayOf(0.0, 0.0, 1.0)

    /** R_cw z matice senzora R_dw (zariadenie → svet). */
    fun cameraToWorld(deviceToWorld: Mat3): Mat3 = deviceToWorld * Mat3.diag(1.0, -1.0, -1.0)

    /** Optická os (smer pohľadu) vo svete. */
    fun opticalAxis(rCw: Mat3): DoubleArray = rCw.col(2)

    /**
     * Náklon okolo optickej osi v stupňoch: 0 = vodorovná os obrazu je vodorovne (mobil rovno na výšku),
     * kladné = natočené v smere hodinových ručičiek pri pohľade cez kameru. Pri pohľade kolmo hore/dole nemá zmysel.
     */
    fun rollDeg(rCw: Mat3): Double {
        val x = rCw.col(0) // os x obrazu vo svete
        val z = rCw.col(2)
        val horiz = Vec.cross(z, UP) // vodorovný smer „doprava“ pre daný pohľad
        if (Vec.norm(horiz) < 1e-6) return 0.0
        val h = Vec.normalize(horiz)
        val downish = Vec.cross(z, h)
        return Math.toDegrees(atan2(Vec.dot(x, downish), Vec.dot(x, h)))
    }

    /** Zorné pole (stupne) z ohniska v pixeloch a rozmeru v pixeloch. */
    fun fovDeg(fPx: Double, sizePx: Double) = Math.toDegrees(2 * atan2(sizePx / 2, fPx))

    fun focalPx(fovDeg: Double, sizePx: Double) = (sizePx / 2) / kotlin.math.tan(Math.toRadians(fovDeg) / 2)
}

/**
 * Súradnice panorámy: `forward` = vodorovný smer prvého záberu (stred obrázka), `right` = doprava od neho,
 * `up` = zvislo hore. Smer pre azimut `yaw` (doprava kladný) a výšku `pitch` (hore kladná):
 *   d = cos(pitch)·(cos(yaw)·forward + sin(yaw)·right) + sin(pitch)·up
 */
class PanoFrame(val forward: DoubleArray, val right: DoubleArray, val up: DoubleArray) {

    fun dir(yawDeg: Double, pitchDeg: Double): DoubleArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        val ch = cos(p)
        return doubleArrayOf(
            ch * (cos(y) * forward[0] + sin(y) * right[0]) + sin(p) * up[0],
            ch * (cos(y) * forward[1] + sin(y) * right[1]) + sin(p) * up[1],
            ch * (cos(y) * forward[2] + sin(y) * right[2]) + sin(p) * up[2],
        )
    }

    /** Vráti (yawDeg v −180..180, pitchDeg v −90..90) pre svetový smer. */
    fun yawPitch(d: DoubleArray): DoubleArray {
        val n = Vec.normalize(d)
        val pitch = Math.toDegrees(asin(Vec.dot(n, up).coerceIn(-1.0, 1.0)))
        val yaw = Math.toDegrees(atan2(Vec.dot(n, right), Vec.dot(n, forward)))
        return doubleArrayOf(yaw, pitch)
    }

    /** Matica svet → panoráma: riadky = right, up, forward (súradnice x doprava, y hore, z dopredu). */
    fun worldToPano() = Mat3(
        doubleArrayOf(
            right[0], right[1], right[2],
            up[0], up[1], up[2],
            forward[0], forward[1], forward[2],
        )
    )

    companion object {
        /** Rám panorámy z prvého záberu. Ak sa prvý záber pozerá takmer zvislo, použije sa horná hrana obrazu. */
        fun fromFirstFrame(rCw: Mat3): PanoFrame {
            val up = SphereGeo.UP
            var f = SphereGeo.opticalAxis(rCw)
            var h = doubleArrayOf(f[0], f[1], 0.0)
            if (Vec.norm(h) < 0.2) {
                // pohľad skoro kolmo hore/dole: „dopredu“ je tam, kam mieri horná hrana obrazu (−y kamery)
                f = Vec.scale(rCw.col(1), if (f[2] > 0) 1.0 else -1.0)
                h = doubleArrayOf(f[0], f[1], 0.0)
            }
            val forward = if (Vec.norm(h) < 1e-9) doubleArrayOf(0.0, 1.0, 0.0) else Vec.normalize(h)
            val right = Vec.normalize(Vec.cross(forward, up))
            return PanoFrame(forward, right, up)
        }

        /** R_cw kamery, ktorá sa pozerá smerom (yaw, pitch) s nulovým náklonom. Pri póloch horná hrana mieri k yaw. */
        fun cameraLookingAt(frame: PanoFrame, yawDeg: Double, pitchDeg: Double): Mat3 {
            val z = frame.dir(yawDeg, pitchDeg)
            var x = Vec.cross(z, frame.up)
            if (abs(pitchDeg) > 89.9 || Vec.norm(x) < 1e-6) {
                x = Vec.cross(frame.dir(yawDeg, 0.0), frame.up)
            }
            x = Vec.normalize(x)
            val y = Vec.normalize(Vec.cross(z, x)) // dole v obraze
            return Mat3.fromCols(x, y, z)
        }
    }
}
