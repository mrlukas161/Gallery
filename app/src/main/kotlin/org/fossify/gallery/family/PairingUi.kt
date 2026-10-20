package org.fossify.gallery.family

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.value
import org.fossify.commons.views.MyEditText
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.faces.ZxingDecoder
import java.io.File

// Pomocné funkcie obrazovky párovania (QR, skenovanie fotkou, dialógy v štýle appky).

private const val SCAN_MAX_PX = 2048
private const val SECONDS_PER_MINUTE = 60

// QR kód čierny na bielom; null pri chybe kódovania
internal fun familyQrBitmap(text: String, size: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val width = matrix.width
    val pixels = IntArray(width * matrix.height) {
        if (matrix.get(it % width, it / width)) Color.BLACK else Color.WHITE
    }
    Bitmap.createBitmap(pixels, width, matrix.height, Bitmap.Config.ARGB_8888)
} catch (ignored: WriterException) {
    null
}

// QR z fotky (záloha bez skenera): zmenšené na rozumnú veľkosť, druhý pokus ešte menšie. Volať na pozadí.
internal fun decodeFamilyQr(file: File): String? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > SCAN_MAX_PX) sample *= 2
    val decoder = ZxingDecoder()
    return listOf(sample, sample * 2).firstNotNullOfOrNull { size ->
        val options = BitmapFactory.Options().apply { inSampleSize = size }
        BitmapFactory.decodeFile(file.absolutePath, options)?.let { decoder.decodeFirst(it)?.text }
    }
}

// spustí fotoaparát (ACTION_IMAGE_CAPTURE) s výstupom do `file`; false = žiadna aplikácia fotoaparátu
internal fun Activity.startFamilyScan(file: File, requestCode: Int): Boolean {
    file.delete()
    val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)
    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
        true
    } catch (ignored: ActivityNotFoundException) {
        false
    } catch (ignored: SecurityException) {
        false
    }
}

// „4:59“
internal fun formatRemaining(remainingMs: Long): String {
    val seconds = remainingMs / DateUtils.SECOND_IN_MILLIS
    return "${seconds / SECONDS_PER_MINUTE}:" + (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
}

internal fun Activity.showFamilyMessageDialog(
    titleRes: Int,
    message: String,
    positiveRes: Int,
    onPositive: () -> Unit,
) {
    val padding = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
    val view = TextView(this).apply {
        text = message
        setTextColor(getProperTextColor())
        setPadding(padding, padding, padding, 0)
    }
    getAlertDialogBuilder()
        .setPositiveButton(positiveRes) { _, _ -> onPositive() }
        .setNegativeButton(org.fossify.commons.R.string.cancel, null)
        .apply { setupDialogStuff(view, this, titleRes) }
}

internal fun Activity.promptFamilyText(titleRes: Int, current: String, hintRes: Int, onText: (String) -> Unit) {
    val input = MyEditText(this).apply {
        setSingleLine()
        setText(current)
        setHint(hintRes)
        setSelection(current.length)
    }
    val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
    val wrapper = LinearLayout(this).apply {
        setPadding(margin, margin / 2, margin, 0)
        addView(
            input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }
    getAlertDialogBuilder()
        .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> onText(input.value) }
        .setNegativeButton(org.fossify.commons.R.string.cancel, null)
        .apply { setupDialogStuff(wrapper, this, titleRes) }
}
