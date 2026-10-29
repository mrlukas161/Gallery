package org.fossify.gallery.family

import android.content.Context
import android.provider.MediaStore
import org.fossify.commons.extensions.isImageFast
import org.fossify.gallery.clip.ClipDatabase
import org.fossify.gallery.clip.ClipEntity
import org.fossify.gallery.faces.AssignmentSource
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FaceAssignmentEntity
import org.fossify.gallery.faces.FaceBoxSpace
import org.fossify.gallery.faces.FaceEmbedVersion
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.FaceTakenAt
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.GeoDatabase
import org.fossify.gallery.faces.GeoEntity
import org.fossify.gallery.faces.IgnoredFaceEntity
import org.fossify.gallery.faces.IndexScope
import org.fossify.gallery.faces.IndexedPhotoEntity
import org.fossify.gallery.faces.OcrDatabase
import org.fossify.gallery.faces.OcrIndexer
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashEntity
import org.fossify.gallery.faces.QrDatabase
import org.fossify.gallery.faces.QrScanner
import org.fossify.gallery.faces.UprightDecoder
import java.io.File

// Zapíše výsledky z balíka k lokálnej fotke (F4, spec 2.3) VÝHRADNE cez existujúce DAO metódy, bez zmeny
// schém. Zápis riadku do indexu je zároveň značka „spracovaná“ (indexed_photos pri tvárach, riadok v ocr/
// geo/phash/clip/qr pri ostatných), takže indexery fotku preskočia — procesor nerobí nič druhýkrát.
// Čo už prijímateľ má, sa NIKDY neprepisuje (BundleRules.plan). Tváre sa len pridávajú k fotke, ktorá ešte
// žiadne nemá, takže pomenované tváre prijímateľa sa nedajú poškodiť. Volá sa len z pozadia.
@Suppress("TooManyFunctions")
class BundleApplier(context: Context) {
    private val app = context.applicationContext
    private val scope = IndexScope(app)
    private val faceDao by lazy { FacesDatabase.getInstance(app).FaceDao() }

    // localPhash = pHash prijímateľovej kópie, ak ho vypočítala záloha (FUZZY) — uloží sa namiesto cudzieho.
    // names (D5) = ako zapísať mená (séria bez EXIF), fromDeviceId/inboxId (D6) = pôvod priradení.
    // Vráti, čo sa naozaj zapísalo.
    @Suppress("LongParameterList")
    fun apply(
        path: String,
        bundle: PhotoBundle,
        match: BundleMatch,
        localPhash: Long? = null,
        mediaStoreId: Long? = null,
        names: NamePolicy = NamePolicy.FULL,
        fromDeviceId: String? = null,
        inboxId: String? = null,
    ): TakePlan {
        val origin = Origin(names, fromDeviceId, inboxId)
        // fotka v zamknutom/vylúčenom priečinku: indexery ju nespracúvajú, výsledky sa tam nezapíšu ani z balíka
        if (!scope.allows(path) || !File(path).isFile) return TakePlan()
        val image = path.isImageFast()
        val have = localResults(path, image)
        val plan = BundleRules.plan(bundle, match, have, image, FaceEmbedder.MODEL_ID, FamilyModels.CLIP_MODEL_ID)
        val now = System.currentTimeMillis()
        val ocr = bundle.ocr
        val geo = bundle.geo
        val qr = bundle.qr
        val wrote = TakePlan(
            faces = plan.faces &&
                guarded { writeFaces(path, bundle.faces.orEmpty(), mediaStoreId, now, origin) } == true,
            ocr = plan.ocr && ocr != null &&
                guarded { OcrIndexer.saveResult(app, path, ocr, overwrite = false) } == true,
            geo = plan.geo && geo != null && guarded {
                GeoDatabase.getInstance(app).GeoDao().insert(GeoEntity(path, geo.lat, geo.lon, geo.hasGeo))
            } != null,
            phash = writePhash(path, bundle, plan, match, have, localPhash, now),
            clip = plan.clip && writeClip(path, bundle, now),
            qr = plan.qr && qr != null && guarded { QrScanner.store(app, path, qr) } != null,
        )
        // D4: fotku už spracoval prijímateľ sám (napr. kópia z WhatsAppu) → aspoň mená cez párovanie tvárí
        if (image && have.faces && !plan.faces) guarded { takeNames(path, bundle.faces.orEmpty(), match, now, origin) }
        // obsahový kľúč fotky si zapamätaj (F3 podľa neho páruje priradenia tvárí z op-logu)
        guarded { ContentKeyStore(FamilyDatabase.getInstance(app).contentKeyDao()).keyFor(path) }
        return wrote
    }

    // pôvod a politika mien balíka
    private class Origin(val names: NamePolicy, val deviceId: String?, val inboxId: String?)

    private fun takeNames(path: String, faces: List<BundleFace>, match: BundleMatch, now: Long, origin: Origin) {
        if (faces.isEmpty()) return
        val family = FamilyDatabase.getInstance(app)
        val local = FamilyFaces(app, faceDao, ContentKeyStore(family.contentKeyDao()), family.faceSpaceDao())
            .localBoxes(path)
        if (local.isEmpty()) return
        val minIou = if (match == BundleMatch.EXACT) FaceMatch.MIN_IOU_EXACT else FaceMatch.MIN_IOU
        val pairs = BundleNames.pair(faces, local, minIou)
        val state = LocalFamilyState(app)
        BundleNames.writes(faces, pairs, origin.names, { state.localPersonId(it) }, { state.assignment(it) })
            .forEach { write(it, now, origin) }
    }

    private fun write(w: BundleNames.Write, now: Long, origin: Origin) {
        // E2: priradenie z balíka prepojeného telefónu
        val entity = FaceAssignmentEntity(w.faceId, w.personId, w.manual, now, AssignmentSource.FAMILY)
        PeopleDatabase.getInstance(app).PeopleDao().upsertAssignment(entity)
        val device = origin.deviceId ?: return
        FamilyDatabase.getInstance(app).remoteAssignmentDao()
            .upsert(RemoteOrigin.record(entity, device, RemoteOrigin.bundleOpId(origin.inboxId.orEmpty())))
    }

    private fun localResults(path: String, image: Boolean): LocalResults {
        if (!image) return LocalResults(geo = has { GeoDatabase.getInstance(app).GeoDao().getRow(path) })
        return LocalResults(
            faces = has { path.takeIf { faceDao.getFaceCountForPath(it) > 0 || it in faceDao.getProcessedPaths() } },
            ocr = has { OcrDatabase.getInstance(app).OcrDao().getText(path) },
            geo = has { GeoDatabase.getInstance(app).GeoDao().getRow(path) },
            phash = has { PhashDatabase.getInstance(app).PhashDao().getByPath(path) },
            clip = has { ClipDatabase.getInstance(app).ClipDao().getByPath(path) },
            qr = has { QrDatabase.getInstance(app).QrDao().getByPath(path) },
        )
    }

    // Tváre v relatívnych súradniciach → absolútne pre túto kópiu (môže byť zmenšená) v priestore, v akom ich
    // ukladá FaceIndexer (UprightDecoder.decode(path, 1024)). Rovnaká poistka id ako pri indexovaní.
    private fun writeFaces(
        path: String,
        faces: List<BundleFace>,
        mediaStoreId: Long?,
        now: Long,
        origin: Origin,
    ): Boolean {
        if (faces.isNotEmpty()) {
            if (!FacesDatabase.ensureIdsAboveReferences(app)) return false
            val size = UprightDecoder.uprightSize(path, FaceMatch.DECODE_MAX_SIZE) ?: return false
            val storeId = mediaStoreId ?: mediaStoreIdFor(path) ?: 0L
            // E1: rámčeky sú v upright v2 priestore tejto kópie (box_space = 2), odtlačky vo verzii uloženej
            // tu (balík sa prijíma len pri rovnakom MODEL_ID), kvalitu doplní FaceMetaBackfill
            val takenAt = guarded { FaceTakenAt.of(app, path, storeId) }
            val embedVersion = FaceEmbedVersion.storedVersion()
            val entities = faces.mapIndexed { i, f ->
                val box = BundleRules.absoluteBox(f.box, size[0], size[1])
                val embedding = f.embeddingB64?.let { FamilyBytes.unb64(it) }
                FaceEntity(
                    id = null,
                    mediaFullPath = path,
                    mediaStoreId = storeId,
                    faceIndex = i,
                    bboxLeft = box[0],
                    bboxTop = box[1],
                    bboxRight = box[2],
                    bboxBottom = box[3],
                    score = f.score,
                    embedding = embedding,
                    detectedAt = now,
                    boxSpace = FaceBoxSpace.UPRIGHT_V2,
                    embedVersion = if (embedding != null) embedVersion else null,
                    takenAt = takenAt,
                )
            }
            // ABORT pri kolízii (indexer medzitým našiel tváre sám) → výnimka, nič sa nezapíše
            faceDao.insertFaces(entities)
        }
        faceDao.insertIndexedPhoto(IndexedPhotoEntity(path, faces.size, now))
        if (faces.isNotEmpty()) guarded { assign(path, faces, now, origin) }
        return true
    }

    // priradenia osobám podľa rodinného UUID (neznáma osoba = bez priradenia; ručné priradenia prídu aj
    // cez op-log F3 a spárujú sa po FamilySync.retryPending) a „odložené“ tváre
    // D5: pri sérii bez EXIF len automaticky alebo bez mien (origin.names); D6: pôvod priradení
    private fun assign(path: String, faces: List<BundleFace>, now: Long, origin: Origin) {
        val inserted = faceDao.getFacesForPath(path).associateBy { it.faceIndex }
        val state = LocalFamilyState(app)
        val extras = ExtrasDatabase.getInstance(app).ExtrasDao()
        val names = BundleNames.forNewFaces(faces, origin.names) { state.localPersonId(it) }
        faces.forEachIndexed { i, f ->
            val faceId = inserted[i]?.id ?: return@forEachIndexed
            if (f.ignored) extras.insertIgnored(IgnoredFaceEntity(faceId))
            names[i]?.let { write(it.copy(faceId = faceId), now, origin) }
        }
    }

    private fun writePhash(
        path: String,
        bundle: PhotoBundle,
        plan: TakePlan,
        match: BundleMatch,
        have: LocalResults,
        localPhash: Long?,
        now: Long,
    ): Boolean {
        val hash = when {
            plan.phash -> bundle.phash
            match == BundleMatch.FUZZY && !have.phash && path.isImageFast() -> localPhash?.takeIf { it != 0L }
            else -> null
        } ?: return false
        return guarded { PhashDatabase.getInstance(app).PhashDao().insert(PhashEntity(path, hash, now)) } != null
    }

    // clip.db neeviduje model pri vektore (schéma sa nemení), preto si model prijatých vektorov pamätá family.db
    private fun writeClip(path: String, bundle: PhotoBundle, now: Long): Boolean {
        val clip = bundle.clip ?: return false
        val bytes = FamilyBytes.unb64(clip.vectorB64)?.takeIf { it.size == CLIP_BYTES } ?: return false
        val ok = guarded { ClipDatabase.getInstance(app).ClipDao().insert(ClipEntity(path, bytes, now)) } != null
        if (ok) {
            guarded {
                val meta = FamilyDatabase.getInstance(app).metaDao()
                val merged = FamilyModels.mergeClipModel(meta.get(KEY_CLIP_MODEL), clip.modelId)
                meta.put(MetaEntity(KEY_CLIP_MODEL, merged))
            }
        }
        return ok
    }

    private fun mediaStoreIdFor(path: String): Long? = guarded {
        val uri = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val column = MediaStore.MediaColumns.DATA
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), "$column = ?", arrayOf(path), null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    // pri chybe čítania sa ráta, že výsledok UŽ je (radšej nič nezapísať, než prepísať vlastné výsledky)
    @Suppress("TooGenericExceptionCaught")
    private fun has(block: () -> Any?): Boolean = try {
        block() != null
    } catch (e: Exception) {
        android.util.Log.w(TAG, "stav indexu sa nedá prečítať", e)
        true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w(TAG, "zápis výsledku z balíka zlyhal", e)
        null
    }

    companion object {
        // model CLIP vektorov prijatých z prepojeného telefónu (meta tabuľka family.db)
        const val KEY_CLIP_MODEL = "f4_clip_model"
        private const val TAG = "BundleApplier"

        // ClipEntity: 512 float32 = 2 KB (ClipEncoder.toBytes, poradie bajtov zariadenia — na všetkých
        // Android ABI little-endian, rovnako ako odtlačky tvárí v FaceEntity.embedding)
        private const val CLIP_BYTES = 512 * 4
    }
}
