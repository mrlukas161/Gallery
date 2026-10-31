package org.fossify.gallery.compare

import kotlin.math.min

// Výrez panela v RELATÍVNYCH súradniciach: stred výrezu ako podiel šírky/výšky fotky (0–1) a priblíženie
// voči „celá fotka v paneli“ (1 = fit). Takto sa dá priblíženie preniesť medzi fotkami s rôznym
// rozlíšením aj pomerom strán a ukáže to isté miesto scény (synchronizácia, prepnutie fotky, obnova stavu).
data class RelativeView(val centerX: Float, val centerY: Float, val zoom: Float) {
    val isZoomed: Boolean get() = zoom > ZOOMED

    companion object {
        val FIT = RelativeView(HALF, HALF, 1f)
        private const val ZOOMED = 1.05f
    }
}

// Absolútny stav panela pre GestureImageView: posun x/y v pixeloch panela a priblíženie (px panela na px obrázka).
data class AbsoluteView(val x: Float, val y: Float, val zoom: Float)

// Čistá geometria panelov (PaneMathTest). Rozmery: v = panel (viewport), i = obrázok v paneli (drawable).
object PaneMath {
    fun fitZoom(vw: Float, vh: Float, iw: Float, ih: Float): Float {
        val valid = min(vw, vh) > 0f && min(iw, ih) > 0f
        return if (valid) min(vw / iw, vh / ih) else 1f
    }

    fun toRelative(vw: Float, vh: Float, iw: Float, ih: Float, state: AbsoluteView): RelativeView {
        if (iw <= 0f || ih <= 0f || state.zoom <= 0f) return RelativeView.FIT
        val cx = (vw / 2f - state.x) / state.zoom / iw
        val cy = (vh / 2f - state.y) / state.zoom / ih
        return RelativeView(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), state.zoom / fitZoom(vw, vh, iw, ih))
    }

    fun toAbsolute(vw: Float, vh: Float, iw: Float, ih: Float, rel: RelativeView): AbsoluteView {
        val zoom = rel.zoom * fitZoom(vw, vh, iw, ih)
        return AbsoluteView(vw / 2f - rel.centerX * iw * zoom, vh / 2f - rel.centerY * ih * zoom, zoom)
    }

    // viditeľná časť obrázka [l, t, r, b] v súradniciach obrázka (orezaná na obrázok)
    fun visibleRect(vw: Float, vh: Float, iw: Float, ih: Float, state: AbsoluteView): FloatArray {
        if (state.zoom <= 0f) return floatArrayOf(0f, 0f, iw, ih)
        val l = (-state.x / state.zoom).coerceIn(0f, iw)
        val t = (-state.y / state.zoom).coerceIn(0f, ih)
        val r = ((vw - state.x) / state.zoom).coerceIn(0f, iw)
        val b = ((vh - state.y) / state.zoom).coerceIn(0f, ih)
        return floatArrayOf(l, t, r, b)
    }

    // panely pod sebou (true) alebo vedľa seba: podľa toho, kde budú fotky typického pomeru strán väčšie
    // (pri remíze rozhoduje tvar priestoru — na výšku pod sebou)
    fun stacked(containerW: Float, containerH: Float, aspect: Float, gap: Float = 0f): Boolean {
        if (containerW <= 0f || containerH <= 0f) return containerH >= containerW
        val a = if (aspect > 0f) aspect else DEFAULT_ASPECT
        val stackedArea = shownArea(containerW, (containerH - gap) / 2f, a)
        val sideArea = shownArea((containerW - gap) / 2f, containerH, a)
        return if (stackedArea == sideArea) containerH >= containerW else stackedArea > sideArea
    }

    // plocha fotky (šírka/výška = aspect) vpasovanej do w × h
    private fun shownArea(w: Float, h: Float, aspect: Float): Float {
        val shownW = min(w, h * aspect)
        return shownW * (shownW / aspect)
    }

    // typický pomer strán skupiny = medián (jedna panoráma nezmení rozloženie)
    fun medianAspect(aspects: List<Float>): Float {
        val valid = aspects.filter { it > 0f }.sorted()
        return if (valid.isEmpty()) DEFAULT_ASPECT else valid[valid.size / 2]
    }

    private const val DEFAULT_ASPECT = 4f / 3f
}

private const val HALF = 0.5f
