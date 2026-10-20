package org.fossify.gallery.family

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// Ako sa fotka z balíka našla u prijímateľa (spec 2.1): EXACT = rovnaký obsahový kľúč,
// FUZZY = len záloha (pHash + dátum, napr. fotka prekomprimovaná WhatsAppom).
enum class BundleMatch { EXACT, FUZZY }

// Čo už prijímateľ k fotke má. Vlastné výsledky prijímateľa sa NIKDY neprepisujú.
data class LocalResults(
    val faces: Boolean = false,
    val ocr: Boolean = false,
    val geo: Boolean = false,
    val phash: Boolean = false,
    val clip: Boolean = false,
    val qr: Boolean = false,
)

// Ktoré časti balíka sa zapíšu.
data class TakePlan(
    val faces: Boolean = false,
    val ocr: Boolean = false,
    val geo: Boolean = false,
    val phash: Boolean = false,
    val clip: Boolean = false,
    val qr: Boolean = false,
) {
    fun any(): Boolean = faces || ocr || geo || phash || clip || qr
}

// Rámček tváre tak, ako je vo faces.db (priestor bitmapy z UprightDecoder.decode(path, 1024) alebo, pri
// starých záznamoch na otočených fotkách, surový neotočený priestor), s časom detekcie.
data class StoredBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val detectedAt: Long)

// Rozhodovanie F4 (BundleBuilder, BundleReceiver, FamilyInbox). Čistá logika bez Androidu (BundleRulesTest).
object BundleRules {
    // Tváre detegované od tohto času (detected_at) sú vždy v upright priestore: UprightDecoder používa
    // FaceIndexer od 0.47.0 (commit 9b8ca33, 30.7.2026) a RefreshFaces nové tváre vkladá tiež upright.
    // Mesiac rezervy na neskoršiu aktualizáciu appky. Pozor: RefreshFaces pri oprave rámčeka na mieste
    // detected_at NEMENÍ, preto starší detected_at sám o sebe nič nedokazuje (rozhodne až veľkosť rámčeka).
    const val UPRIGHT_SINCE_MS = 1_788_134_400_000L // 31.8.2026 00:00 UTC

    // rámček môže pri nedeliteľných rozmeroch presahovať o 1 px (RelBox.decodedUprightSize)
    private const val EDGE_TOLERANCE = 1

    // balík bez súboru čaká na fotku najviac 30 dní; záloha (pHash) skúša fotky so súborom ± 14 dní od príjmu
    const val WAIT_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000
    const val FUZZY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    private const val EXIF_DATE = "yyyy:MM:dd HH:mm:ss"

    // Spec 2.3: vlastné výsledky sa neprepisujú; tváre len pri rovnakom faceModelId; OCR len pri presnej
    // zhode (pri zmenšení môže text zmiznúť); pHash len pri presnej zhode (prekomprimovaná kópia má vlastný,
    // ten si prijímateľ pri zálohe aj tak vypočítal); CLIP len pri rovnakom modeli. Video: len poloha.
    // localClipModel = model, ktorý by prijímateľ použil (appka má jediný; ak CLIP ešte nemá, stiahne práve ten).
    fun plan(
        bundle: PhotoBundle,
        match: BundleMatch,
        have: LocalResults,
        isImage: Boolean,
        localFaceModel: String,
        localClipModel: String,
    ): TakePlan {
        val geo = bundle.geo != null && !have.geo
        if (!isImage) return TakePlan(geo = geo)
        val exact = match == BundleMatch.EXACT
        return TakePlan(
            faces = takeFaces(bundle, have, localFaceModel),
            ocr = exact && bundle.ocr != null && !have.ocr,
            geo = geo,
            phash = exact && bundle.phash != null && !have.phash,
            clip = bundle.clip?.modelId == localClipModel && !have.clip,
            qr = bundle.qr != null && !have.qr,
        )
    }

    private fun takeFaces(bundle: PhotoBundle, have: LocalResults, localFaceModel: String): Boolean =
        bundle.faces != null && bundle.faceModelId == localFaceModel && !have.faces

    // Relatívne upright rámčeky tvárí jednej fotky (rawWidth × rawHeight z hlavičky súboru, EXIF transformácia),
    // alebo null, keď priestor aspoň jedného rámčeka nejde určiť (spec 2.3: také tváre sa neposielajú celé,
    // prijímateľ si fotku spracuje sám). Neotočená fotka: rámček je jednoznačný. Otočená: upright, keď
    // detected_at ≥ UPRIGHT_SINCE_MS; inak rozhodne veľkosť — rámček, ktorý sa zmestí len do jedného z
    // priestorov, patrí doň (surový sa prepočíta do upright), a ak sa zmestí do oboch, je nejasný.
    fun uprightBoxes(
        faces: List<StoredBox>,
        rawWidth: Int,
        rawHeight: Int,
        transform: RelBox.Transform,
    ): List<RelBox>? {
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val max = FaceMatch.DECODE_MAX_SIZE
        val rotated = Math.floorMod(transform.rotationDegrees, FULL_TURN) != 0 || transform.mirrored
        val upright = RelBox.decodedUprightSize(rawWidth, rawHeight, max, transform.rotationDegrees)
        val raw = RelBox.decodedUprightSize(rawWidth, rawHeight, max, 0)
        return faces.map { f ->
            val asUpright = RelBox.fromAbsolute(f.left, f.top, f.right, f.bottom, upright[0], upright[1])
            if (!rotated || f.detectedAt >= UPRIGHT_SINCE_MS) return@map asUpright
            val fitsUpright = fits(f, upright)
            val fitsRaw = fits(f, raw)
            when {
                fitsUpright && !fitsRaw -> asUpright
                fitsRaw && !fitsUpright -> RelBox.fromAbsolute(f.left, f.top, f.right, f.bottom, raw[0], raw[1])
                    .transformed(transform.rotationDegrees, transform.mirrored)

                else -> return null
            }
        }
    }

    // Prepočet relatívneho rámčeka na absolútny pre kópiu prijímateľa: width × height = rozmery bitmapy
    // z UprightDecoder.decode(path, 1024), teda UprightDecoder.uprightSize(path, FaceMatch.DECODE_MAX_SIZE).
    fun absoluteBox(box: RelBox, uprightWidth: Int, uprightHeight: Int): IntArray =
        box.toAbsolute(uprightWidth, uprightHeight)

    // Súkromie (spec 2.5, doplnok k IndexScope): nič z koša a nič skryté (súbor alebo priečinok s bodkou).
    fun isSendablePath(path: String, recycleBinPath: String?): Boolean {
        if (path.isEmpty()) return false
        if (!recycleBinPath.isNullOrEmpty() && path.startsWith(recycleBinPath)) return false
        return path.split('/').none { it.startsWith('.') }
    }

    // Dátum nasnímania z EXIF („2026:10:20 14:03:05“) v ms v danom časovom pásme, null = chýba/poškodený.
    fun parseExifDate(value: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val text = value?.trim()
        if (text.isNullOrEmpty() || text.startsWith("0000")) return null
        val format = SimpleDateFormat(EXIF_DATE, Locale.US).apply {
            isLenient = false
            timeZone = zone
        }
        return try {
            format.parse(text)?.time?.takeIf { it > 0L }
        } catch (ignored: ParseException) {
            null
        }
    }

    // fotka so súborom z tohto obdobia sa skúša spárovať s čakajúcim balíkom aj cez zálohu (pHash)
    fun inFuzzyWindow(fileMtimeMs: Long, receivedAtMs: Long): Boolean =
        abs(fileMtimeMs - receivedAtMs) <= FUZZY_WINDOW_MS

    fun isExpired(receivedAtMs: Long, nowMs: Long): Boolean = nowMs - receivedAtMs > WAIT_EXPIRY_MS

    private const val FULL_TURN = 360

    private fun fits(f: StoredBox, size: IntArray): Boolean =
        f.right <= size[0] + EDGE_TOLERANCE && f.bottom <= size[1] + EDGE_TOLERANCE
}
