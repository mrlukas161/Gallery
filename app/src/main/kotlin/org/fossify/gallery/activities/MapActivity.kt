package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import java.io.File
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityMapBinding
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.GeoDatabase
import org.fossify.gallery.faces.GeoEntity
import org.fossify.gallery.faces.GeoIndexer
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.QrDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import kotlin.math.floor
import kotlin.math.pow

// Mapa fotiek podľa GPS (osmdroid / OpenStreetMap). Pri oddialení sa fotky zhlukujú (clustering).
class MapActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMapBinding::inflate)
    private var points: List<GeoEntity> = emptyList()
    private var filterPaths: HashSet<String>? = null
    private var filterPerson: Set<Long>? = null
    private var filterQrOnly = false
    private val iconCache = HashMap<Int, BitmapDrawable>()
    private val thumbCache = HashMap<String, BitmapDrawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(binding.root)
        filterPaths = org.fossify.gallery.helpers.PathTransfer.forMap?.toHashSet()
        org.fossify.gallery.helpers.PathTransfer.forMap = null
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(6.0)
        binding.mapView.controller.setCenter(GeoPoint(48.7, 19.7))
        binding.mapView.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                redraw()
                return false
            }
        }, 300))
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mapAppbar, NavigationIcon.Arrow)
        if (filterPaths != null) binding.mapToolbar.title = getString(R.string.map_selection)
        binding.mapToolbar.menu.clear()
        binding.mapToolbar.inflateMenu(R.menu.menu_map)
        binding.mapToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.map_filter) {
                showFilterDialog()
                true
            } else {
                false
            }
        }
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun load() {
        ensureBackgroundThread {
            points = loadPoints()
            runOnUiThread {
                if (!isDestroyed) {
                    redraw()
                    fitToPoints()
                }
            }
        }
        if (filterPaths == null && !GeoIndexer.isRunning) {
            binding.mapStatus.text = getString(R.string.map_indexing, 0, 0)
            binding.mapStatus.beVisible()
            GeoIndexer.index(
                this,
                onProgress = { done, total ->
                    runOnUiThread { if (!isDestroyed) binding.mapStatus.text = getString(R.string.map_indexing, done, total) }
                },
                onDone = { _, _ ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            binding.mapStatus.beGone()
                            reloadPoints()
                        }
                    }
                },
                onError = { runOnUiThread { if (!isDestroyed) binding.mapStatus.beGone() } },
            )
        }
    }

    private fun loadPoints(): List<GeoEntity> {
        val all = try {
            GeoDatabase.getInstance(this).GeoDao().getGeotagged()
        } catch (e: Throwable) {
            emptyList()
        }
        var result = filterPaths?.let { fp -> all.filter { fp.contains(it.path) } } ?: all
        result = applyMapFilters(result)
        return result
    }

    // filter priamo na mape: osoby (mená) + len s QR kódom
    private fun applyMapFilters(geos: List<GeoEntity>): List<GeoEntity> {
        var result = geos
        val fp = filterPerson
        if (fp != null && fp.isNotEmpty()) {
            val photoPersons = try {
                val facesDao = FacesDatabase.getInstance(this).FaceDao()
                val peopleDao = PeopleDatabase.getInstance(this).PeopleDao()
                val pidByFace = peopleDao.getAssignments().associate { it.faceId to it.personId }
                val pp = HashMap<String, MutableSet<Long>>()
                for (f in facesDao.getAllFaces()) {
                    val id = f.id ?: continue
                    val pid = pidByFace[id] ?: continue
                    pp.getOrPut(f.mediaFullPath) { HashSet() }.add(pid)
                }
                pp
            } catch (e: Throwable) {
                emptyMap<String, MutableSet<Long>>()
            }
            result = result.filter { geo -> photoPersons[geo.path]?.any { fp.contains(it) } == true }
        }
        if (filterQrOnly) {
            val qrSet = try {
                QrDatabase.getInstance(this).QrDao().getPathsWithQr().toHashSet()
            } catch (e: Throwable) {
                HashSet()
            }
            result = result.filter { qrSet.contains(it.path) }
        }
        return result
    }

    private fun showFilterDialog() {
        ensureBackgroundThread {
            val persons = try {
                PeopleDatabase.getInstance(this).PeopleDao().getPersons()
                    .filter { !it.name.isNullOrBlank() }
                    .sortedBy { it.name ?: "" }
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val labels = ArrayList<String>()
                persons.forEach { labels.add(it.name ?: "#${it.id}") }
                labels.add(getString(R.string.filter_qr_code))
                val qrIndex = labels.size - 1
                val checked = BooleanArray(labels.size) { i ->
                    if (i == qrIndex) {
                        filterQrOnly
                    } else {
                        val pid = persons[i].id
                        pid != null && filterPerson?.contains(pid) == true
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.filter_media)
                    .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val sel = HashSet<Long>()
                        persons.forEachIndexed { i, p -> if (checked[i]) p.id?.let { sel.add(it) } }
                        filterPerson = if (sel.isEmpty()) null else sel
                        filterQrOnly = checked[qrIndex]
                        binding.mapToolbar.title =
                            if (filterPerson != null || filterQrOnly) getString(R.string.map_selection)
                            else getString(R.string.app_name_brand)
                        reloadPoints()
                    }
                    .setNeutralButton(R.string.clear_filter) { _, _ ->
                        filterPerson = null
                        filterQrOnly = false
                        reloadPoints()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun reloadPoints() {
        ensureBackgroundThread {
            val p = loadPoints()
            runOnUiThread {
                if (!isDestroyed) {
                    points = p
                    redraw()
                    if (p.isNotEmpty()) fitToPoints()
                }
            }
        }
    }

    private fun redraw() {
        if (isDestroyed) return
        binding.mapView.overlays.clear()
        val zoom = binding.mapView.zoomLevelDouble
        for (cluster in cluster(points, cellSizeDeg(zoom))) {
            val marker = Marker(binding.mapView)
            marker.position = GeoPoint(cluster.lat, cluster.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = makeClusterIcon(cluster.paths.size)
            val cl = cluster
            if (cluster.paths.size <= THUMB_MAX) {
                // Google Photos štýl: malé clustre = náhľad reprezentatívnej fotky (+ počet)
                loadThumbInto(marker, cluster.paths.first(), cluster.paths.size)
            }
            marker.setOnMarkerClickListener { _, _ ->
                onClusterTap(cl)
                true
            }
            binding.mapView.overlays.add(marker)
        }
        binding.mapView.invalidate()
    }

    private fun fitToPoints() {
        if (points.isEmpty()) return
        binding.mapView.post {
            try {
                val north = points.maxOf { it.lat }
                val south = points.minOf { it.lat }
                val east = points.maxOf { it.lon }
                val west = points.minOf { it.lon }
                if (north == south && east == west) {
                    binding.mapView.controller.setZoom(15.0)
                    binding.mapView.controller.setCenter(GeoPoint(north, east))
                } else {
                    binding.mapView.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 80)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    // Google Photos štýl: pinch-zoom rozpadá clustre; ŤUK na cluster ho OTVORÍ ako uzavretú galériu.
    private fun onClusterTap(cluster: Cluster) {
        if (cluster.paths.size == 1) {
            openPhotoStandard(cluster.paths.first())
            return
        }
        org.fossify.gallery.helpers.PathTransfer.forGrid = cluster.paths.take(2000)
        startActivity(Intent(this, PhotoGridActivity::class.java))
    }

    private fun openPhotoStandard(path: String) {
        org.fossify.gallery.helpers.PathTransfer.forViewer = listOf(path)
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(org.fossify.gallery.helpers.PATH, path)
            putExtra(org.fossify.gallery.helpers.SKIP_AUTHENTICATION, true)
            putExtra(org.fossify.gallery.helpers.SHOW_ALL, false)
            startActivity(this)
        }
    }

    private data class Cluster(val lat: Double, val lon: Double, val paths: List<String>)

    // veľkosť zhlukovacej bunky v stupňoch ~ konštantná v pixeloch (adaptívne podľa zoomu) -> pri
    // väčšom priblížení menšie bunky = viac menších, lepšie umiestnených clusterov.
    private fun cellSizeDeg(zoom: Double): Double {
        val degPerPixel = 360.0 / (256.0 * 2.0.pow(zoom.coerceIn(1.0, 21.0)))
        return (degPerPixel * 90.0).coerceAtLeast(1e-7)
    }

    private fun cluster(pts: List<GeoEntity>, cell: Double): List<Cluster> {
        if (pts.isEmpty()) return emptyList()
        val map = HashMap<String, MutableList<GeoEntity>>()
        for (p in pts) {
            val gx = floor(p.lon / cell).toLong()
            val gy = floor(p.lat / cell).toLong()
            map.getOrPut("$gx,$gy") { mutableListOf() }.add(p)
        }
        return map.values.map { list ->
            Cluster(list.map { it.lat }.average(), list.map { it.lon }.average(), list.map { it.path })
        }
    }

    private fun makeClusterIcon(count: Int): BitmapDrawable {
        iconCache[count]?.let { return it }
        val size = (48 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1976D2") }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2, circle)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = size * 0.05f
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2, ring)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = if (count >= 100) size * 0.3f else size * 0.38f
        }
        val y = size / 2f - (tp.descent() + tp.ascent()) / 2f
        c.drawText(count.toString(), size / 2f, y, tp)
        val drawable = BitmapDrawable(resources, bmp)
        iconCache[count] = drawable
        return drawable
    }

    // načíta náhľad reprezentatívnej fotky do markera (async, cachované)
    private fun loadThumbInto(marker: Marker, path: String, count: Int) {
        val key = "$path|$count"
        thumbCache[key]?.let { marker.icon = it; return }
        ensureBackgroundThread {
            val d = makeThumbDrawable(path, count)
            runOnUiThread {
                if (!isDestroyed && d != null) {
                    thumbCache[key] = d
                    marker.icon = d
                    binding.mapView.invalidate()
                }
            }
        }
    }

    private fun makeThumbDrawable(path: String, count: Int): BitmapDrawable? {
        return try {
            val size = (58 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val src = decodeSquare(path, size) ?: return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
            val radius = size * 0.16f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.06f
            paint.color = Color.WHITE
            c.drawRoundRect(rect, radius, radius, paint)
            src.recycle()
            if (count > 1) {
                val br = size * 0.24f
                val bx = size - br - 2f
                val by = br + 2f
                c.drawCircle(bx, by, br, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E01976D2") })
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                    textSize = br * 1.05f
                }
                val ty = by - (tp.descent() + tp.ascent()) / 2f
                c.drawText(if (count > 99) "99+" else count.toString(), bx, ty, tp)
            }
            BitmapDrawable(resources, out)
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeSquare(path: String, size: Int): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > size * 2 || h / sample > size * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val dim = minOf(bmp.width, bmp.height)
        val x = (bmp.width - dim) / 2
        val y = (bmp.height - dim) / 2
        val sq = try {
            Bitmap.createBitmap(bmp, x, y, dim, dim)
        } catch (e: Throwable) {
            bmp
        }
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        if (scaled != sq && sq != bmp) sq.recycle()
        if (scaled != bmp && bmp != sq) bmp.recycle()
        return scaled
    }

    companion object {
        const val FILTER_PATHS = "filter_paths"
        private const val MAX_ZOOM = 19.0
        private const val THUMB_MAX = 25
    }
}
