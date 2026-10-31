package org.fossify.gallery.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

// "AI ostrosť" = rozptyl Laplaciánu po dlaždiciach, horný kvartil (ImageStats.sharpness). Vyššie = ostrejšie.
object Sharpness {
    private const val DEFAULT_SIDE = 256

    // samostatné meranie zo súboru (dekóduje fotku); porovnávač meria z už dekódovanej bitmapy (ofBitmap)
    fun score(path: String, maxSide: Int = DEFAULT_SIDE): Double {
        if (!File(path).exists()) return 0.0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0.0
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return 0.0
        val gray = grayOf(bmp)
        bmp.recycle()
        return ImageStats.sharpness(gray)
    }

    // ostrosť v jednotnej mierke (dlhšia strana ImageStats.SHARPNESS_SIDE), aby boli porovnateľné aj fotky
    // s rôznym rozlíšením; gray = sivotónová verzia bitmapy (ak ju volajúci už má)
    fun ofGray(gray: GrayImage): Double = ImageStats.sharpness(ImageStats.downscale(gray, ImageStats.SHARPNESS_SIDE))

    fun grayOf(bmp: Bitmap): GrayImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return GrayImage(ImageStats.toGray(px), bmp.width, bmp.height)
    }
}
