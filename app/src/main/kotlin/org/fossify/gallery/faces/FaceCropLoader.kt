package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File

// Načíta výrez tváre (avatar) — dekóduje fotku v ROVNAKOM zmenšení ako indexer (max 1024),
// takže uložený bbox sedí, a oreže tvár. Cacheuje, aby grid neblikal.
object FaceCropLoader {
    private const val MAX_DECODE_SIZE = 1024

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(face: FaceEntity, imageView: ImageView) {
        val key = "${face.mediaFullPath}#${face.faceIndex}"
        imageView.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageBitmap(null)
        ensureBackgroundThread {
            val bmp = try {
                decodeCrop(face)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                imageView.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun decodeCrop(face: FaceEntity): Bitmap? {
        val path = face.mediaFullPath
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > MAX_DECODE_SIZE || h / sample > MAX_DECODE_SIZE) sample *= 2
        val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val pad = ((face.bboxRight - face.bboxLeft) * 0.25f).toInt()
        val l = (face.bboxLeft - pad).coerceIn(0, full.width)
        val t = (face.bboxTop - pad).coerceIn(0, full.height)
        val r = (face.bboxRight + pad).coerceIn(l, full.width)
        val b = (face.bboxBottom + pad).coerceIn(t, full.height)
        if (r <= l || b <= t) return full
        val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
        if (crop !== full) full.recycle()
        return crop
    }
}
