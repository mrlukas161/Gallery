package org.fossify.gallery.family

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.gallery.clip.ClipDatabase
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.GeoDatabase
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashIndexer
import org.fossify.gallery.faces.QrDatabase
import java.io.File
import java.io.IOException

// Zostaví balík hotových výsledkov k fotke (F4, spec 2.3) z faces/people/extras a ocr/geo/phash/clip/qr.
// Len čítanie, nič sa tu nespracúva nanovo (výnimka: pHash, keď chýba — je lacný a slúži na záložnú zhodu,
// výsledok sa u odosielateľa neukladá). Volá sa len z pozadia. Jedna inštancia na jedno odoslanie fronty
// (cache priradení a spracovaných ciest), nie je thread-safe.
class BundleBuilder(context: Context) {
    private val app = context.applicationContext
    private val family = FamilyDatabase.getInstance(app)
    private val keys = ContentKeyStore(family.contentKeyDao())
    private val scope = IndexScope(app)
    private val faceDao by lazy { FacesDatabase.getInstance(app).FaceDao() }
    private val processedFaces by lazy { faceDao.getProcessedPaths().toHashSet() }
    private val assignments by lazy {
        PeopleDatabase.getInstance(app).PeopleDao().getAssignments().associateBy { it.faceId }
    }
    private val ignored by lazy { ExtrasDatabase.getInstance(app).ExtrasDao().getIgnoredIds().toHashSet() }

    // D9: CLIP vektory prijaté z rodiny v inom modeli → clip.db je zmiešaná, CLIP sa neposiela
    private val clipSendable by lazy {
        FamilyModels.canSendClip(safe { family.metaDao().get(BundleApplier.KEY_CLIP_MODEL) })
    }

    // D3: overenie priestoru rámčekov odtlačkom pri odosielaní (len otočené fotky s nejasným priestorom)
    private val verifier by lazy { FaceSpaceVerifier(app) }
    private var verifierUsed = false
    private val familyFaces by lazy {
        FamilyFaces(app, faceDao, keys, family.faceSpaceDao(), verifier = verifier.also { verifierUsed = true })
    }

    // Spec 2.5: zamknuté/vylúčené priečinky, kôš a skryté súbory sa neposielajú nikdy, ani ich výsledky.
    fun isAllowed(path: String): Boolean =
        BundleRules.isSendablePath(path, safe { app.recycleBinPath }) && scope.allows(path)

    // null = fotka sa nesmie alebo nedá poslať (mimo rozsahu, neexistuje, nedá sa prečítať)
    fun build(path: String): PhotoBundle? {
        if (!isAllowed(path)) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = keys.keyFor(path) ?: return null
        val image = path.isImageFast()
        val faces = if (image) safe { faces(path) } else null
        return PhotoBundle(
            contentKey = key,
            name = file.name,
            mime = path.getMimeType(),
            size = file.length(),
            takenAtMs = if (image) takenAt(path) else null,
            faceModelId = faces?.let { FaceEmbedder.MODEL_ID },
            faces = faces,
            ocr = if (image) safe { OcrDatabase.getInstance(app).OcrDao().getText(path) } else null,
            geo = safe { GeoDatabase.getInstance(app).GeoDao().getRow(path) }
                ?.let { BundleGeo(it.lat, it.lon, it.hasGeo) },
            phash = if (image) phash(path) else null,
            clip = if (image) clip(path) else null,
            qr = if (image) safe { QrDatabase.getInstance(app).QrDao().getByPath(path) }?.qrText else null,
        )
    }

    // Osoby priradené tváram fotky (aby mali rodinné UUID ešte pred odoslaním balíka, F3 OpRecorder).
    fun assignedPersons(path: String): Set<Long> =
        faceDao.getFacesForPath(path).mapNotNullTo(HashSet()) { f -> f.id?.let { assignments[it]?.personId } }

    // null = tváre fotky ešte nie sú spracované alebo priestor rámčekov nejde určiť (prijímateľ si ich nájde sám)
    private fun faces(path: String): List<BundleFace>? {
        val stored = faceDao.getFacesForPath(path).sortedBy { it.faceIndex }
        if (stored.isEmpty()) return if (path in processedFaces) emptyList() else null
        val geometry = readPhotoGeometry(path) ?: return null
        val boxes = BundleRules.uprightBoxes(
            stored.map { storedBox(it) },
            geometry.rawWidth,
            geometry.rawHeight,
            geometry.transform,
            cached = { familyFaces.cachedSpace(stored[it]) },
            verify = { familyFaces.verifyNow(stored[it], geometry) },
        ) ?: return null
        val persons = family.familyPersonDao()
        return stored.zip(boxes) { face, box -> bundleFace(face, box, persons) }
    }

    private fun bundleFace(face: FaceEntity, box: RelBox, persons: FamilyPersonDao): BundleFace {
        val assignment = face.id?.let { assignments[it] }
        return BundleFace(
            box = box,
            score = face.score,
            embeddingB64 = face.embedding?.let { FamilyBytes.b64(it) },
            familyUuid = assignment?.let { persons.getPersonByLocalId(it.personId)?.familyUuid },
            manual = assignment?.isManual == true,
            ignored = face.id?.let { it in ignored } == true,
        )
    }

    private fun phash(path: String): Long? {
        val stored = safe { PhashDatabase.getInstance(app).PhashDao().getByPath(path) }?.hashValue
        val hash = stored?.takeIf { it != 0L } ?: safe { PhashIndexer.computeHash(path) }
        return hash?.takeIf { it != 0L }
    }

    private fun clip(path: String): BundleClip? {
        if (!clipSendable) return null
        val row = safe { ClipDatabase.getInstance(app).ClipDao().getByPath(path) } ?: return null
        return BundleClip(FamilyModels.CLIP_MODEL_ID, FamilyBytes.b64(row.embedding))
    }

    private fun takenAt(path: String): Long? = safe { exifTakenAt(path) }

    // uvoľní modely tvárí (ak sa overovalo); builder sa potom už nepoužíva
    fun close() {
        if (verifierUsed) verifier.close()
    }

    // jedna chýbajúca časť (poškodená DB, nečitateľný súbor) nesmie zhodiť celý balík
    @Suppress("TooGenericExceptionCaught")
    private fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "časť balíka sa nedá prečítať", e)
        null
    }

    private companion object {
        const val TAG = "BundleBuilder"
    }
}

// Dátum nasnímania z EXIF v ms (rovnako na oboch telefónoch, záloha FuzzyMatch), null = chýba.
internal fun exifTakenAt(path: String): Long? = try {
    val exif = ExifInterface(path)
    BundleRules.parseExifDate(
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
    )
} catch (ignored: IOException) {
    null
}
