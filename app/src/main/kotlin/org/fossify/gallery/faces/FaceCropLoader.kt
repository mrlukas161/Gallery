package org.fossify.gallery.faces

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.util.LruCache
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

// Zdieľaný pool na dekódovanie náhľadov tvárí (FaceCropLoader aj FaceContextLoader).
// Predtým sa pri KAŽDOM binde bunky zakladalo nové vlákno (ensureBackgroundThread) — pri rýchlom
// scrolle mriežky tak bežali desiatky vlákien a plných dekódovaní naraz; pool drží záťaž na uzde.
internal val faceThumbExecutor: ExecutorService = Executors.newFixedThreadPool(3)

// Bežiaca úloha pre každý ImageView — pri rebinde/recyklácii bunky sa predchádzajúca zruší,
// nech sa nedokončuje dekódovanie, ktorého výsledok už nikto nezobrazí. Zdieľané oboma loadermi
// (FaceTagAdapter prepína tú istú bunku medzi výrezom a kontextom).
internal val facePendingLoads: MutableMap<ImageView, Future<*>> =
    Collections.synchronizedMap(WeakHashMap<ImageView, Future<*>>())

// Načíta výrez tváre (avatar) — bbox je uložený v UPRIGHT priestore zmenšenom na max 1024
// (rovnako ako pri indexovaní), takže výrez sedí. Cacheuje, aby grid neblikal.
object FaceCropLoader {
    private const val MAX_DECODE_SIZE = 1024

    // výrez tváre do mriežky netreba väčší — menšie dekódovanie aj viac položiek v cache
    private const val MAX_CROP_OUT = 512

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(face: FaceEntity, imageView: ImageView) {
        val key = "${face.mediaFullPath}#${face.faceIndex}"
        imageView.tag = key
        // bunka dostáva nový obsah — zruš prípadnú starú úlohu z recyklácie
        facePendingLoads.remove(imageView)?.cancel(false)
        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.setImageBitmap(null)
        val future = faceThumbExecutor.submit {
            // bunka sa medzitým recyklovala na inú tvár — nedekóduj zbytočne
            if (imageView.tag != key) return@submit
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
        facePendingLoads[imageView] = future
    }

    private fun decodeCrop(face: FaceEntity): Bitmap? {
        val path = face.mediaFullPath
        if (!File(path).exists()) return null
        // Rýchla cesta: BitmapRegionDecoder dekóduje LEN okolie tváre namiesto celej fotky.
        val region = try {
            decodeCropRegion(face)
        } catch (e: Throwable) {
            null
        }
        if (region != null) return region

        // Fallback = pôvodné plné dekódovanie (zrkadlené EXIF orientácie, formáty bez podpory
        // region decodera, poškodené súbory...).
        // MUSÍ to byť rovnaké dekódovanie ako pri indexovaní (upright podľa EXIF), inak by výrez
        // sedel na iné miesto — presne to spôsobovalo tváre „nabok" pred verziou 0.47.
        val full = UprightDecoder.decode(path, MAX_DECODE_SIZE)?.bitmap ?: return null
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

    // Bbox je uložený v upright priestore zmenšenom na max 1024. Tu ho premietneme späť do
    // surových súradníc plného rozlíšenia, dekódujeme IBA tento región a otočíme ho — výsledok
    // je rovnaký výrez ako z celej fotky, ale bez dekódovania celej fotky.
    private fun decodeCropRegion(face: FaceEntity): Bitmap? {
        val path = face.mediaFullPath
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        // zrkadlené orientácie nechávame na fallback (vzácne; inverzné mapovanie so zrkadlením
        // by bolo náchylné na chyby a chyba = výrez na nesprávnom mieste)
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null
        // rovnaký výpočet inSampleSize ako UprightDecoder — v tomto zmenšení sú uložené bboxy
        var sample = 1
        while (rawW / sample > MAX_DECODE_SIZE || rawH / sample > MAX_DECODE_SIZE) sample *= 2
        val scaledW = rawW / sample
        val scaledH = rawH / sample
        val uprightW = if (deg == 90 || deg == 270) scaledH else scaledW
        val uprightH = if (deg == 90 || deg == 270) scaledW else scaledH

        // rovnaký padding ako pri plnom dekódovaní
        val pad = ((face.bboxRight - face.bboxLeft) * 0.25f).toInt()
        val ul = (face.bboxLeft - pad).coerceIn(0, uprightW)
        val ut = (face.bboxTop - pad).coerceIn(0, uprightH)
        val ur = (face.bboxRight + pad).coerceIn(ul, uprightW)
        val ub = (face.bboxBottom + pad).coerceIn(ut, uprightH)
        if (ur <= ul || ub <= ut) return null

        // inverzia otočenia: upright bbox -> surové súradnice (stále v zmenšenom priestore);
        // dopredný smer je v UprightDecoder.mapBox, toto je jeho presný opak
        val rawBox = when (deg) {
            90 -> intArrayOf(ut, scaledH - ur, ub, scaledH - ul)
            180 -> intArrayOf(scaledW - ur, scaledH - ub, scaledW - ul, scaledH - ut)
            270 -> intArrayOf(scaledW - ub, ul, scaledW - ut, ur)
            else -> intArrayOf(ul, ut, ur, ub)
        }
        val fullRect = Rect(
            (rawBox[0] * sample).coerceIn(0, rawW),
            (rawBox[1] * sample).coerceIn(0, rawH),
            (rawBox[2] * sample).coerceIn(0, rawW),
            (rawBox[3] * sample).coerceIn(0, rawH),
        )
        if (fullRect.width() <= 0 || fullRect.height() <= 0) return null

        // výrez netreba vo väčšom rozlíšení než MAX_CROP_OUT — prípadne ešte pridaj sampling
        var regionSample = sample
        while (fullRect.width() / regionSample > MAX_CROP_OUT || fullRect.height() / regionSample > MAX_CROP_OUT) {
            regionSample *= 2
        }
        val decoder = BitmapRegionDecoder.newInstance(path, false) ?: return null
        val rawCrop: Bitmap? = try {
            decoder.decodeRegion(fullRect, BitmapFactory.Options().apply {
                inSampleSize = regionSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            decoder.recycle()
        }
        if (rawCrop == null) return null
        if (deg == 0) return rawCrop
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(rawCrop, 0, 0, rawCrop.width, rawCrop.height, m, true)
        if (rotated !== rawCrop) rawCrop.recycle()
        return rotated
    }
}
