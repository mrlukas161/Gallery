package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.widget.ImageView
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// Zobrazí CELÚ fotku so zvýrazneným rámikom okolo konkrétnej tváre — aby bolo jasné, o ktorú
// tvár ide, keď je na fotke ľudí viac. Dekóduje rovnako ako indexer (upright), takže rámik sedí.
object FaceContextLoader {
    private const val MAX_DECODE_SIZE = 1024
    private const val OUT_SIZE = 480

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(face: FaceEntity, imageView: ImageView) {
        val key = "ctx:${face.mediaFullPath}#${face.faceIndex}"
        imageView.tag = key
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageBitmap(null)
        ensureBackgroundThread {
            val bmp = try {
                render(face)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                imageView.post {
                    if (imageView.tag == key) imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun render(face: FaceEntity): Bitmap? {
        val path = face.mediaFullPath
        if (!File(path).exists()) return null
        val full = UprightDecoder.decode(path, MAX_DECODE_SIZE)?.bitmap ?: return null
        val out = try {
            full.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Throwable) {
            null
        } ?: return null
        if (out !== full) full.recycle()

        val c = Canvas(out)
        val stroke = (maxOf(out.width, out.height) * 0.008f).coerceAtLeast(3f)
        // tmavý podklad + svetlý rámik = viditeľné na svetlej aj tmavej fotke
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 2f
            color = Color.parseColor("#99000000")
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.parseColor("#FF4CD964")
        }
        val r = RectF(
            face.bboxLeft.toFloat(), face.bboxTop.toFloat(),
            face.bboxRight.toFloat(), face.bboxBottom.toFloat(),
        )
        val radius = stroke * 2f
        c.drawRoundRect(r, radius, radius, shadow)
        c.drawRoundRect(r, radius, radius, border)

        val longEdge = maxOf(out.width, out.height)
        if (longEdge <= OUT_SIZE) return out
        val sc = OUT_SIZE.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            out, (out.width * sc).toInt().coerceAtLeast(1), (out.height * sc).toInt().coerceAtLeast(1), true,
        )
        if (scaled !== out) out.recycle()
        return scaled
    }
}
