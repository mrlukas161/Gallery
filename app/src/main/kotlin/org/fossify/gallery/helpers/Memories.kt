package org.fossify.gallery.helpers

import android.content.Context
import android.provider.MediaStore
import java.util.Calendar

// „Spomienky" ako v Google Photos — poskladané LOKÁLNE z dátumov a polohy fotiek:
//  • Pred rokom / Pred N rokmi — ten istý deň v minulých rokoch,
//  • Výlety — súvislé dni s fotkami mimo domáceho okolia (podľa GPS),
//  • Vydarené dni — dni s najväčším počtom fotiek.
// Žiadny cloud, žiadne modely — len MediaStore a geo.db.
object Memories {

    data class Memory(val title: String, val subtitle: String, val paths: List<String>, val rank: Int)

    private data class Shot(val path: String, val time: Long, val lat: Double, val lon: Double, val hasGeo: Boolean)

    private const val MIN_PHOTOS = 4
    private const val TRIP_MIN_KM = 60.0

    fun build(context: Context): List<Memory> {
        val shots = loadShots(context)
        if (shots.isEmpty()) return emptyList()
        val out = ArrayList<Memory>()
        out.addAll(anniversaries(context, shots))
        out.addAll(trips(context, shots))
        out.addAll(bigDays(context, shots, exclude = out.flatMap { it.paths }.toHashSet()))
        return out.sortedBy { it.rank }
    }

    // ---- „Pred rokom" (a viac) : rovnaký deň ±2 dni v predchádzajúcich rokoch ----
    private fun anniversaries(context: Context, shots: List<Shot>): List<Memory> {
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_YEAR)
        val year = now.get(Calendar.YEAR)
        val byYear = HashMap<Int, MutableList<Shot>>()
        val cal = Calendar.getInstance()
        for (s in shots) {
            cal.timeInMillis = s.time
            val y = cal.get(Calendar.YEAR)
            if (y >= year) continue
            val d = cal.get(Calendar.DAY_OF_YEAR)
            if (kotlin.math.abs(d - today) <= 2) byYear.getOrPut(y) { ArrayList() }.add(s)
        }
        return byYear.entries
            .filter { it.value.size >= MIN_PHOTOS }
            .sortedByDescending { it.key }
            .map { (y, list) ->
                val diff = year - y
                val title = if (diff == 1) {
                    context.getString(org.fossify.gallery.R.string.memory_year_ago)
                } else {
                    context.getString(org.fossify.gallery.R.string.memory_years_ago, diff)
                }
                Memory(
                    title,
                    context.getString(org.fossify.gallery.R.string.memory_photos, y, list.size),
                    list.sortedBy { it.time }.map { it.path },
                    rank = 0,
                )
            }
    }

    // ---- Výlety: súvislé dni, kde fotky ležia ďaleko od „domáceho" ťažiska ----
    private fun trips(context: Context, shots: List<Shot>): List<Memory> {
        val geo = shots.filter { it.hasGeo }
        if (geo.size < 20) return emptyList()
        // domov = medián polohy (odolný voči výletom)
        val homeLat = geo.map { it.lat }.sorted()[geo.size / 2]
        val homeLon = geo.map { it.lon }.sorted()[geo.size / 2]

        val far = geo.filter { distanceKm(it.lat, it.lon, homeLat, homeLon) > TRIP_MIN_KM }.sortedBy { it.time }
        if (far.size < MIN_PHOTOS) return emptyList()

        val groups = ArrayList<MutableList<Shot>>()
        var cur = ArrayList<Shot>()
        for (s in far) {
            if (cur.isEmpty() || s.time - cur.last().time <= 2 * 24 * 3600_000L) {
                cur.add(s)
            } else {
                groups.add(cur)
                cur = arrayListOf(s)
            }
        }
        if (cur.isNotEmpty()) groups.add(cur)

        return groups.filter { it.size >= MIN_PHOTOS }
            .sortedByDescending { it.last().time }
            .take(6)
            .map { g ->
                Memory(
                    context.getString(org.fossify.gallery.R.string.memory_trip),
                    context.getString(
                        org.fossify.gallery.R.string.memory_trip_sub,
                        formatDate(g.first().time), g.size,
                    ),
                    g.map { it.path },
                    rank = 1,
                )
            }
    }

    // ---- Vydarené dni: dni s najviac fotkami (bez tých, ktoré už sú vo výletoch/výročiach) ----
    private fun bigDays(context: Context, shots: List<Shot>, exclude: Set<String>): List<Memory> {
        val cal = Calendar.getInstance()
        val byDay = HashMap<String, MutableList<Shot>>()
        for (s in shots) {
            if (exclude.contains(s.path)) continue
            cal.timeInMillis = s.time
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            byDay.getOrPut(key) { ArrayList() }.add(s)
        }
        return byDay.values
            .filter { it.size >= 12 }
            .sortedByDescending { it.size }
            .take(8)
            .map { g ->
                Memory(
                    context.getString(org.fossify.gallery.R.string.memory_day),
                    context.getString(
                        org.fossify.gallery.R.string.memory_trip_sub,
                        formatDate(g.first().time), g.size,
                    ),
                    g.sortedBy { it.time }.map { it.path },
                    rank = 2,
                )
            }
    }

    private fun loadShots(context: Context): List<Shot> {
        val geoByPath = try {
            org.fossify.gallery.faces.GeoDatabase.getInstance(context).GeoDao().getGeotagged()
                .associateBy { it.path }
        } catch (e: Throwable) {
            emptyMap()
        }
        val list = ArrayList<Shot>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                val dData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val dTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dMod = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val p = c.getString(dData) ?: continue
                    val taken = if (dTaken >= 0) c.getLong(dTaken) else 0L
                    val mod = if (dMod >= 0) c.getLong(dMod) * 1000L else 0L
                    val t = if (taken > 0) taken else mod
                    if (t <= 0) continue
                    val g = geoByPath[p]
                    list.add(Shot(p, t, g?.lat ?: 0.0, g?.lon ?: 0.0, g != null))
                }
            }
        } catch (ignored: Throwable) {
        }
        return list
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun formatDate(time: Long): String =
        java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault()).format(java.util.Date(time))
}
