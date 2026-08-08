package org.fossify.gallery.activities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityTextSelectBinding
import org.fossify.gallery.faces.OcrEngine
import org.fossify.gallery.faces.OcrIndexer
import org.fossify.gallery.faces.UprightDecoder
import org.fossify.gallery.helpers.TextNormalizer

// Výber textu PRIAMO na fotke (ako Xiaomi galéria): slová sú orámované, ťukaním si vyberieš,
// ktoré chceš, a skopíruješ LEN tie. Slová zodpovedajúce hľadanému výrazu sú predznačené.
class TextSelectActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityTextSelectBinding::inflate)
    private var view: SelectView? = null
    private var currentBmp: Bitmap? = null
    private var photoPath = ""
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        photoPath = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        searchQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        startRecognition()
    }

    // Rozpoznanie textu (aj opakované po zlyhaní). Rozlišuje dve situácie: „na fotke nie je text"
    // (definitívny výsledok — uloží sa prázdny záznam) a „OCR zlyhalo" (chyba enginu — nič sa
    // neukladá a stavový riadok ponúkne nový pokus ťuknutím).
    private fun startRecognition() {
        binding.textSelectStatus.setOnClickListener(null)
        binding.textSelectStatus.isClickable = false
        binding.textSelectStatus.text = getString(R.string.live_text_reading)
        ensureBackgroundThread {
            var engine: OcrEngine? = null
            var ocrFailed = false
            val bmp = UprightDecoder.decode(photoPath, 1600)?.bitmap
            val words = try {
                if (bmp != null) {
                    engine = OcrEngine(this)
                    if (engine.isReady()) {
                        engine.recognizeWords(bmp)
                    } else {
                        // engine sa nepodarilo inicializovať — to NIE je „fotka bez textu"
                        ocrFailed = true
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } catch (e: Throwable) {
                ocrFailed = true
                emptyList()
            } finally {
                try {
                    engine?.close()
                } catch (ignored: Throwable) {
                }
            }
            if (bmp != null && !ocrFailed) {
                // [27] výsledok on-demand OCR nezahodiť: ulož ho do ocr.db rovnakou cestou ako
                // indexer, nech sa fotka zaradí do Dokumentov a nájde ju hľadanie. Fotka bez
                // textu dostane prázdny záznam (konvencia indexera — OCR sa už nebude opakovať).
                // overwrite=false: existujúci záznam indexera sa nikdy neprepíše.
                OcrIndexer.saveResult(this, photoPath, wordsToText(words), overwrite = false)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp == null) {
                    toast(R.string.action_failed)
                    finish()
                    return@runOnUiThread
                }
                currentBmp = bmp
                val v = SelectView(this, bmp, words, searchQuery) { selectedCount ->
                    binding.textSelectStatus.text = if (selectedCount == 0) {
                        getString(if (words.isEmpty()) R.string.text_select_none else R.string.text_select_hint)
                    } else {
                        getString(R.string.selected_count, selectedCount)
                    }
                }
                view = v
                binding.textSelectContainer.addView(
                    v,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
                if (ocrFailed) {
                    binding.textSelectStatus.text = getString(R.string.text_select_ocr_failed)
                    binding.textSelectStatus.setOnClickListener { retryRecognition() }
                } else {
                    binding.textSelectStatus.text =
                        getString(if (words.isEmpty()) R.string.text_select_none else R.string.text_select_hint)
                }
            }
        }
    }

    // Nový pokus po zlyhaní OCR: odstráň starý pohľad, uvoľni bitmapu a rozpoznaj odznova.
    private fun retryRecognition() {
        binding.textSelectContainer.removeAllViews()
        view = null
        try {
            currentBmp?.recycle()
        } catch (ignored: Throwable) {
        }
        currentBmp = null
        startRecognition()
    }

    // Poskladá text z boxov slov (Tesseract ich vracia v poradí čítania) do viacriadkovej podoby
    // na uloženie do ocr.db — nový riadok tam, kde sa boxy zvislo neprekrývajú.
    private fun wordsToText(words: List<OcrEngine.WordBox>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        var prev: OcrEngine.WordBox? = null
        for (w in words) {
            val p = prev
            if (p != null) {
                val overlap = minOf(p.bottom, w.bottom) - maxOf(p.top, w.top)
                val minHeight = minOf(p.bottom - p.top, w.bottom - w.top)
                sb.append(if (minHeight > 0 && overlap < minHeight / 2) '\n' else ' ')
            }
            sb.append(w.text)
            prev = w
        }
        return sb.toString()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.textSelectAppbar, NavigationIcon.Arrow)
        binding.textSelectToolbar.menu.clear()
        binding.textSelectToolbar.inflateMenu(R.menu.menu_text_select)
        binding.textSelectToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.text_copy -> {
                    copySelected()
                    true
                }

                R.id.text_select_all -> {
                    view?.selectAll()
                    true
                }

                R.id.text_clear -> {
                    view?.clearSelection()
                    true
                }

                else -> false
            }
        }
    }

    private fun copySelected() {
        val text = view?.selectedText().orEmpty()
        if (text.isBlank()) {
            toast(R.string.compare_marked_none)
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", text))
            toast(org.fossify.commons.R.string.value_copied_to_clipboard)
        } catch (ignored: Throwable) {
        }
    }

    // fotka + boxy slov; ťuk = prepnutie výberu, fit-center mapovanie
    @SuppressLint("ViewConstructor")
    private class SelectView(
        context: Context,
        private val bmp: Bitmap,
        private val words: List<OcrEngine.WordBox>,
        query: String,
        private val onSelection: (Int) -> Unit,
    ) : View(context) {

        private val selected = HashSet<Int>()
        private val matrix = Matrix()
        private val inverse = Matrix()
        private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor("#CCFFD54F")
        }
        private val boxFillSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#664CD964")
        }
        private val boxFillQuery = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#66FF9800")
        }
        private val queryMatches = HashSet<Int>()

        init {
            // predznač slová zodpovedajúce hľadanému výrazu — hneď vidno, KDE na fotke sa nachádza
            val tokens = query.split(Regex("\\s+")).map { TextNormalizer.normalize(it, true) }.filter { it.length >= 2 }
            if (tokens.isNotEmpty()) {
                words.forEachIndexed { i, w ->
                    val n = TextNormalizer.normalize(w.text, true)
                    if (tokens.any { n.contains(it) }) {
                        queryMatches.add(i)
                        selected.add(i)
                    }
                }
                if (selected.isNotEmpty()) post { onSelection(selected.size) }
            }
        }

        fun selectAll() {
            selected.clear()
            words.indices.forEach { selected.add(it) }
            invalidate()
            onSelection(selected.size)
        }

        fun clearSelection() {
            selected.clear()
            invalidate()
            onSelection(0)
        }

        fun selectedText(): String =
            words.indices.filter { selected.contains(it) }.joinToString(" ") { words[it].text }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val scale = minOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            val dx = (w - bmp.width * scale) / 2f
            val dy = (h - bmp.height * scale) / 2f
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)
            matrix.invert(inverse)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, matrix, null)
            val r = RectF()
            words.forEachIndexed { i, wbox ->
                r.set(wbox.left.toFloat(), wbox.top.toFloat(), wbox.right.toFloat(), wbox.bottom.toFloat())
                matrix.mapRect(r)
                when {
                    selected.contains(i) -> canvas.drawRoundRect(r, 4f, 4f, if (queryMatches.contains(i)) boxFillQuery else boxFillSel)
                    else -> canvas.drawRoundRect(r, 4f, 4f, boxStroke)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            val pts = floatArrayOf(event.x, event.y)
            inverse.mapPoints(pts)
            val x = pts[0]
            val y = pts[1]
            var best = -1
            var bestArea = Float.MAX_VALUE
            words.forEachIndexed { i, w ->
                if (x >= w.left - 8 && x <= w.right + 8 && y >= w.top - 8 && y <= w.bottom + 8) {
                    val area = (w.right - w.left).toFloat() * (w.bottom - w.top)
                    if (area < bestArea) {
                        bestArea = area
                        best = i
                    }
                }
            }
            if (best >= 0) {
                if (selected.contains(best)) selected.remove(best) else selected.add(best)
                invalidate()
                onSelection(selected.size)
            }
            return true
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_QUERY = "query"
    }
}
