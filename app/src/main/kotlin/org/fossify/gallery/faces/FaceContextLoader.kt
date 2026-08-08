package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.widget.ImageView
import java.io.File

// Zobrazí CELÚ fotku so zvýrazneným rámikom okolo konkrétnej tváre — aby bolo jasné, o ktorú
// tvár ide, keď je na fotke ľudí viac. Dekóduje rovnako ako indexer (upright), takže rámik sedí.
object FaceContextLoader {
    // v tomto zmenšení indexer ukladal bboxy (UprightDecoder.decode s max 1024)
    private const val INDEX_DECODE_SIZE = 1024
    private const val OUT_SIZE = 480

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(face: FaceEntity, imageView: ImageView) {
        val key = "ctx:${face.mediaFullPath}#${face.faceIndex}"
        imageView.tag = key
        // bunka dostáva nový obsah — zruš prípadnú starú úlohu z recyklácie
        facePendingLoads.remove(imageView)?.cancel(false)
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageBitmap(null)
        val future = faceThumbExecutor.submit {
            // bunka sa medzitým recyklovala na inú tvár — nedekóduj zbytočne
            if (imageView.tag != key) return@submit
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
        facePendingLoads[imageView] = future
    }

    private fun render(face: FaceEntity): Bitmap? {
        val path = face.mediaFullPath
        if (!File(path).exists()) return null
        // Dekóduj rovno na výstupnú veľkosť (OUT_SIZE) namiesto plných 1024 — bbox je ale uložený
        // v priestore max 1024 (ako pri indexovaní), preto ho prepočítame pomerom rozmerov.
        val decoded = UprightDecoder.decode(path, OUT_SIZE) ?: return null
        val full = decoded.bitmap

        // rozmery, v akých indexer uložil bbox (rovnaký výpočet inSampleSize ako UprightDecoder)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > INDEX_DECODE_SIZE || bounds.outHeight / sample > INDEX_DECODE_SIZE) sample *= 2
        val rotated = decoded.rotationDegrees == 90 || decoded.rotationDegrees == 270
        val indexW = (if (rotated) bounds.outHeight else bounds.outWidth) / sample
        val boxScale = if (indexW > 0) full.width.toFloat() / indexW else 1f

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
            face.bboxLeft * boxScale, face.bboxTop * boxScale,
            face.bboxRight * boxScale, face.bboxBottom * boxScale,
        )
        val radius = stroke * 2f
        c.drawRoundRect(r, radius, radius, shadow)
        c.drawRoundRect(r, radius, radius, border)
        // dekódované rovno na OUT_SIZE — dodatočné zmenšovanie už netreba
        return out
    }
}
