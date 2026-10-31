package org.fossify.gallery.compare

import org.fossify.gallery.family.RelBox

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val pixels: Long get() = width.toLong() * height

    fun contains(o: PixelRect) = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom
}

// Plán ostrého výrezu: časť originálu vo vzpriamenom priestore (upright, ako Glide a UprightDecoder),
// ten istý obdĺžnik v surovom súbore (raw, pre BitmapRegionDecoder) a inSampleSize.
data class RegionPlan(val upright: PixelRect, val raw: PixelRect, val sample: Int)

// Kedy a čo dočítať v plnom rozlíšení pri priblížení (RegionMathTest). Základná bitmapa v paneli má
// ~2× rozlíšenie panela; keď je pri priblížení jeden jej pixel väčší ako MIN_ZOOM pixelov panela a originál
// má viac detailu, dočíta sa viditeľný výrez (s okrajom) cez BitmapRegionDecoder — strážený MAX_PIXELS.
object RegionMath {
    const val MIN_ZOOM = 1.15f
    const val MARGIN = 0.25f
    const val MAX_PIXELS = 4_000_000L
    private const val MORE_DETAIL = 1.05f

    // indexy obdĺžnika [l, t, r, b] z PaneMath.visibleRect
    const val LEFT = 0
    const val TOP = 1
    const val RIGHT = 2
    const val BOTTOM = 3

    // visible = PaneMath.visibleRect v súradniciach bitmapy panela (dw × dh), uw × uh = originál vzpriamene,
    // zoom = pixely panela na pixel bitmapy. null = výrez netreba (málo priblížené alebo originál nemá viac).
    fun plan(
        visible: FloatArray,
        dw: Float,
        dh: Float,
        uw: Int,
        uh: Int,
        zoom: Float,
        transform: RelBox.Transform,
    ): RegionPlan? {
        if (dw <= 0f || dh <= 0f || uh <= 0) return null
        if (zoom < MIN_ZOOM || uw <= dw * MORE_DETAIL) return null
        val scale = uw / dw
        val mw = (visible[RIGHT] - visible[LEFT]) * MARGIN
        val mh = (visible[BOTTOM] - visible[TOP]) * MARGIN
        val upright = PixelRect(
            ((visible[LEFT] - mw) * scale).toInt().coerceIn(0, uw),
            ((visible[TOP] - mh) * scale).toInt().coerceIn(0, uh),
            ((visible[RIGHT] + mw) * scale).toInt().coerceIn(0, uw),
            ((visible[BOTTOM] + mh) * scale).toInt().coerceIn(0, uh),
        )
        if (upright.width <= 1 || upright.height <= 1) return null
        // koľko pixelov panela výrez zaberie → stačí toľko pixelov originálu (mocnina 2, ako inSampleSize)
        val screenW = (upright.width / scale) * zoom
        var sample = 1
        while (upright.width / (sample * 2) >= screenW) sample *= 2
        while (upright.pixels / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        return RegionPlan(upright, toRaw(upright, uw, uh, transform), sample)
    }

    // má už zobrazený výrez pokrytý aktuálny pohľad v rovnakej alebo lepšej kvalite?
    fun covers(have: RegionPlan?, want: RegionPlan): Boolean {
        if (have == null) return false
        val visibleCore = shrink(want.upright)
        return have.upright.contains(visibleCore) && have.sample <= want.sample
    }

    // z naplánovaného výrezu s okrajom späť na jadro (viditeľnú časť)
    private fun shrink(r: PixelRect): PixelRect {
        val dx = (r.width * MARGIN / (1f + 2f * MARGIN)).toInt()
        val dy = (r.height * MARGIN / (1f + 2f * MARGIN)).toInt()
        return PixelRect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    // vzpriamený obdĺžnik → surový súbor (opačne ako EXIF otočenie/zrkadlenie v UprightDecoder a Glide)
    fun toRaw(upright: PixelRect, uw: Int, uh: Int, transform: RelBox.Transform): PixelRect {
        val raw = RelBox.uprightSize(uw, uh, transform.rotationDegrees) // 90/270: vymení strany späť
        val box = RelBox.fromAbsolute(upright.left, upright.top, upright.right, upright.bottom, uw, uh)
            .untransformed(transform.rotationDegrees, transform.mirrored)
            .toAbsolute(raw[0], raw[1])
        return PixelRect(box[LEFT], box[TOP], box[RIGHT], box[BOTTOM])
    }
}
