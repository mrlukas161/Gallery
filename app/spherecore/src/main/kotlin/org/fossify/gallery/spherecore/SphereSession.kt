package org.fossify.gallery.spherecore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Relácia snímania = priečinok so zábermi `f_000.jpg`… a súborom `session.json`.
 * Snímanie ju zapisuje priebežne (po každom zábere), skladač ju číta. Formát je verzovaný.
 */
data class SphereLens(
    /** Pomer priblíženia CameraX (1.0 = hlavný objektív, < 1 = širokouhlý). */
    val zoomRatio: Double,
    /** Vodorovné a zvislé zorné pole záberu NA VÝŠKU v stupňoch (odhad pri snímaní). */
    val hFovDeg: Double,
    val vFovDeg: Double,
    /** Rozmery JPEG po otočení podľa EXIF (na výšku: šírka < výška). */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Odkiaľ je zorné pole: "characteristics" | "exif" | "assumed". Skladač ohnisko aj tak doladí. */
    val source: String,
)

data class SphereFrame(
    val file: String,
    /** Index cieľa z [TargetLayout], −1 = záber mimo cieľov. */
    val target: Int,
    val timeMs: Long,
    /** R_cw (kamera → svet) v okamihu záberu, 9 čísel po riadkoch. Pozri SphereMath.kt. */
    val rCw: Mat3,
    /** O koľko stupňov sa mobil pohol počas expozície (kontrola ostrosti). */
    val movedDeg: Double = 0.0,
)

data class SphereSession(
    val id: String,
    val device: String,
    val lens: SphereLens,
    /** "game_rotation_vector" | "rotation_vector". */
    val sensor: String,
    /** Kompasový azimut prvého záberu (0 = sever), null ak nie je známy. */
    val headingDeg: Double?,
    val exposureLocked: Boolean,
    val frames: List<SphereFrame>,
    val startedMs: Long = 0L,
    /** true = používateľ ukončil snímanie (aj predčasne); false = relácia je rozrobená. */
    val finished: Boolean = false,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("id", id)
        o.put("device", device)
        o.put("sensor", sensor)
        if (headingDeg != null) o.put("headingDeg", headingDeg)
        o.put("exposureLocked", exposureLocked)
        o.put("startedMs", startedMs)
        o.put("finished", finished)
        o.put(
            "lens", JSONObject()
                .put("zoomRatio", lens.zoomRatio)
                .put("hFovDeg", lens.hFovDeg)
                .put("vFovDeg", lens.vFovDeg)
                .put("imageWidth", lens.imageWidth)
                .put("imageHeight", lens.imageHeight)
                .put("source", lens.source)
        )
        val arr = JSONArray()
        for (f in frames) {
            val r = JSONArray()
            for (v in f.rCw.m) r.put(v)
            arr.put(
                JSONObject()
                    .put("file", f.file)
                    .put("target", f.target)
                    .put("timeMs", f.timeMs)
                    .put("movedDeg", f.movedDeg)
                    .put("r", r)
            )
        }
        o.put("frames", arr)
        return o.toString(1)
    }

    fun save(dir: File) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(toJson())
        val dst = File(dir, FILE_NAME)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            dst.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "session.json"

        fun fromJson(text: String): SphereSession {
            val o = JSONObject(text)
            val l = o.getJSONObject("lens")
            val lens = SphereLens(
                zoomRatio = l.optDouble("zoomRatio", 1.0),
                hFovDeg = l.getDouble("hFovDeg"),
                vFovDeg = l.getDouble("vFovDeg"),
                imageWidth = l.getInt("imageWidth"),
                imageHeight = l.getInt("imageHeight"),
                source = l.optString("source", "assumed"),
            )
            val arr = o.getJSONArray("frames")
            val frames = ArrayList<SphereFrame>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val r = f.getJSONArray("r")
                frames.add(
                    SphereFrame(
                        file = f.getString("file"),
                        target = f.optInt("target", -1),
                        timeMs = f.optLong("timeMs", 0L),
                        rCw = Mat3(DoubleArray(9) { r.getDouble(it) }),
                        movedDeg = f.optDouble("movedDeg", 0.0),
                    )
                )
            }
            return SphereSession(
                id = o.getString("id"),
                device = o.optString("device", ""),
                lens = lens,
                sensor = o.optString("sensor", ""),
                headingDeg = if (o.has("headingDeg")) o.getDouble("headingDeg") else null,
                exposureLocked = o.optBoolean("exposureLocked", false),
                frames = frames,
                startedMs = o.optLong("startedMs", 0L),
                finished = o.optBoolean("finished", false),
            )
        }

        fun load(dir: File): SphereSession = fromJson(File(dir, FILE_NAME).readText())
    }
}
