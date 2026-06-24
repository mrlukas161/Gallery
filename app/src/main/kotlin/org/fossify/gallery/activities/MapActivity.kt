package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityMapBinding
import org.fossify.gallery.faces.GeoDatabase
import org.fossify.gallery.faces.GeoEntity
import org.fossify.gallery.faces.GeoIndexer
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(binding.root)
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
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun load() {
        ensureBackgroundThread {
            points = try {
                GeoDatabase.getInstance(this).GeoDao().getGeotagged()
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    redraw()
                    fitToPoints()
                }
            }
        }
        if (!GeoIndexer.isRunning) {
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

    private fun reloadPoints() {
        ensureBackgroundThread {
            val p = try {
                GeoDatabase.getInstance(this).GeoDao().getGeotagged()
            } catch (e: Throwable) {
                emptyList()
            }
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
        for (cluster in cluster(points, zoom)) {
            val marker = Marker(binding.mapView)
            marker.position = GeoPoint(cluster.lat, cluster.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = makeClusterIcon(cluster.paths.size)
            val paths = cluster.paths
            marker.setOnMarkerClickListener { _, _ ->
                openCluster(paths)
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

    private fun openCluster(paths: List<String>) {
        val intent = Intent(this, PhotoGridActivity::class.java)
        intent.putStringArrayListExtra(PhotoGridActivity.PATHS, ArrayList(paths))
        startActivity(intent)
    }

    private data class Cluster(val lat: Double, val lon: Double, val paths: List<String>)

    private fun cluster(pts: List<GeoEntity>, zoom: Double): List<Cluster> {
        if (pts.isEmpty()) return emptyList()
        val cell = 180.0 / 2.0.pow(zoom.coerceIn(1.0, 20.0))
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
        return BitmapDrawable(resources, bmp)
    }
}
