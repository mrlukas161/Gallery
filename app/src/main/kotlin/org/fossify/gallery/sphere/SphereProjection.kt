package org.fossify.gallery.sphere

import org.fossify.gallery.spherecore.Mat3
import kotlin.math.atan
import kotlin.math.tan

/** Premietanie svetových smerov do náhľadu (dierková kamera so zorným poľom aktuálneho objektívu). */
object SphereProjection {
    const val MIN_Z = 0.05

    /**
     * Smer `d` (jednotkový, svet) → súradnice v pixeloch obdĺžnika, v ktorom sa zobrazuje obraz z kamery
     * (vľavo, hore, šírka, výška). `out` = x, y, z (z v súradniciach kamery). Vráti true, ak je smer
     * pred kamerou (z > MIN_Z). Obraz na výšku: hFov = šírka obrázka, vFov = výška.
     */
    fun project(
        rCw: Mat3, d: DoubleArray, hFovDeg: Double, vFovDeg: Double,
        left: Double, top: Double, width: Double, height: Double, out: DoubleArray,
    ): Boolean {
        val c = rCw.mulT(d)
        out[2] = c[2]
        if (c[2] <= MIN_Z) return false
        val fx = (width / 2) / tan(Math.toRadians(hFovDeg) / 2)
        val fy = (height / 2) / tan(Math.toRadians(vFovDeg) / 2)
        out[0] = left + width / 2 + c[0] / c[2] * fx
        out[1] = top + height / 2 + c[1] / c[2] * fy
        return true
    }
}

/** Zorné pole objektívu pre obraz na výšku. */
object LensFov {
    data class Fov(val hDeg: Double, val vDeg: Double)

    /** Odhad, keď charakteristiky chýbajú: ekvivalent 24 mm na 35 mm filme. */
    val ASSUMED = Fov(58.0, 73.0)

    /**
     * Zo skutočného ohniska a rozmeru snímača (mm; po zohľadnení aktívnej oblasti). Snímač sa oreže na 4:3.
     * Vráti zorné pole pre portrét pri priblížení 1,0, null pri nezmyselných vstupoch.
     */
    fun fromSensor(focalMm: Double, sensorWmm: Double, sensorHmm: Double): Fov? {
        if (focalMm <= 0 || sensorWmm <= 0 || sensorHmm <= 0) return null
        var long = maxOf(sensorWmm, sensorHmm)
        var short = minOf(sensorWmm, sensorHmm)
        if (long / short > 4.0 / 3.0) long = short * 4.0 / 3.0 else short = long * 3.0 / 4.0
        val landscapeH = Math.toDegrees(2 * atan(long / (2 * focalMm)))
        val landscapeV = Math.toDegrees(2 * atan(short / (2 * focalMm)))
        val f = Fov(hDeg = landscapeV, vDeg = landscapeH) // portrét: šírka obrázka = kratšia strana
        return if (f.hDeg in 20.0..140.0 && f.vDeg in 20.0..160.0) f else null
    }

    /** Priblíženie z: polovica uhla `atan(tan(pol)/z)`. */
    fun withZoom(f: Fov, zoom: Double): Fov {
        if (zoom <= 0 || zoom == 1.0) return f
        fun one(deg: Double) = Math.toDegrees(2 * atan(tan(Math.toRadians(deg) / 2) / zoom))
        return Fov(one(f.hDeg), one(f.vDeg))
    }
}
