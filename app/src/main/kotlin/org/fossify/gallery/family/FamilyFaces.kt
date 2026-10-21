package org.fossify.gallery.family

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.faces.FaceDao
import org.fossify.gallery.faces.FaceEmbedder
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.IndexScope
import java.io.File
import java.io.IOException

// Prevod lokálnej tváre (faces.db) na FaceRef (obsahový kľúč + relatívny rámček) a späť.
// Volá sa len z pozadia: číta hlavičky súborov, EXIF a počíta obsahové kľúče.
// Jedna inštancia na jeden beh (cache rozmerov fotiek), nie je thread-safe.
//  - spaces: uložené overenia priestoru rámčekov (D3, face_space), null = bez nich;
//  - aliases: lokálne cesty pre vzdialený obsahový kľúč (D4, kópie nájdené len cez zálohu, napr. WhatsApp);
//  - verifier: overenie odtlačkom pri odosielaní (refFor), null = neoveruje sa (príjem, párovanie).
class FamilyFaces(
    context: Context,
    private val faceDao: FaceDao,
    private val keys: ContentKeyStore,
    private val spaces: FaceSpaceDao? = null,
    private val aliases: (String) -> List<String> = { emptyList() },
    private val verifier: FaceSpaceVerifier? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = IndexScope(context)
    private val geometry = HashMap<String, PhotoGeometry?>()

    // null = tvár z nepovoleného priečinka (zamknutý/vylúčený, spec 2.5), fotka sa nedá prečítať, alebo priestor
    // rámčeka nie je istý (D2: rovnaké pravidlo ako balíky — jedna hypotéza, alebo nič; nejasná tvár sa vynechá
    // sama, ostatné tváre fotky sa pošlú)
    fun refFor(face: FaceEntity): FaceRef? {
        val path = face.mediaFullPath
        if (!scope.allows(path)) return null
        val box = uprightBox(face) ?: return null
        val key = keys.keyFor(path) ?: return null
        return FaceRef(key, listOf(box))
    }

    // Upright relatívny rámček tváre, keď je jeho priestor istý (pri otočenej fotke aj overením odtlačkom).
    fun uprightBox(face: FaceEntity): RelBox? {
        val g = geometryOf(face.mediaFullPath) ?: return null
        val stored = storedBox(face)
        val space = FaceSpaceRules.resolve(stored, g.rawWidth, g.rawHeight, g.transform, cachedSpace(face)) {
            verifyNow(face, g)
        }
        return FaceSpaceRules.relBox(stored, g.rawWidth, g.rawHeight, g.transform, space)
    }

    // Lokálne id tváre zodpovedajúcej FaceRef, null = fotka s týmto obsahom tu (zatiaľ) nie je.
    // Presná kópia (rovnaký obsahový kľúč) žiada prekryv MIN_IOU_EXACT; kópia nájdená len cez zálohu (D4) MIN_IOU.
    fun findFace(ref: FaceRef): Long? {
        val exact = keys.pathsFor(ref.contentKey)
        for (path in exact) {
            // uložený kľúč mohol zastarať (súbor sa zmenil) — overí sa podľa veľkosti a mtime
            if (keys.keyFor(path) != ref.contentKey) continue
            FaceMatch.best(ref.boxes, localBoxes(path), FaceMatch.MIN_IOU_EXACT)?.let { return it }
        }
        for (path in aliases(ref.contentKey)) {
            if (path in exact || !scope.allows(path) || !File(path).isFile) continue
            FaceMatch.best(ref.boxes, localBoxes(path), FaceMatch.MIN_IOU)?.let { return it }
        }
        return null
    }

    // Tváre fotky ako (id, hypotézy rámčeka) na párovanie: jedna hypotéza, keď je priestor istý, inak obe.
    fun localBoxes(path: String): List<Pair<Long, List<RelBox>>> {
        val g = geometryOf(path) ?: return emptyList()
        return faceDao.getFacesForPath(path).mapNotNull { f ->
            val id = f.id ?: return@mapNotNull null
            FaceSpaceRules.hypotheses(storedBox(f), g.rawWidth, g.rawHeight, g.transform, cachedSpace(f))
                .takeIf { it.isNotEmpty() }?.let { id to it }
        }
    }

    // uložené overenie (D3), len keď sedí verzia modelu aj rámček s odtlačkom
    fun cachedSpace(face: FaceEntity): BoxSpace? {
        val id = face.id ?: return null
        val row = spaces?.get(id) ?: return null
        val stamp = FaceSpaceRules.stamp(storedBox(face), face.embedding)
        return if (row.modelId == FaceEmbedder.MODEL_ID && row.stamp == stamp) BoxSpace.of(row.space) else null
    }

    // overenie odtlačkom teraz (len s verifierom, teda pri odosielaní); výsledok sa uloží do face_space
    internal fun verifyNow(face: FaceEntity, g: PhotoGeometry): BoxSpace? {
        val v = verifier ?: return null
        val id = face.id ?: return null
        val space = v.verify(face, g) ?: return null
        val stamp = FaceSpaceRules.stamp(storedBox(face), face.embedding)
        spaces?.upsert(FaceSpaceEntity(id, space.code, FaceEmbedder.MODEL_ID, clock(), stamp))
        return space
    }

    internal fun geometryOf(path: String): PhotoGeometry? = geometry.getOrPut(path) { readPhotoGeometry(path) }
}

internal fun storedBox(face: FaceEntity) =
    StoredBox(face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom, face.detectedAt)

// Rozmery súboru z hlavičky (bez dekódovania pixelov) a EXIF transformácia, ktorú aplikuje UprightDecoder.
// Spoločné pre FamilyFaces (F3) a BundleBuilder (F4). null = súbor sa nedá prečítať.
internal data class PhotoGeometry(val rawWidth: Int, val rawHeight: Int, val transform: RelBox.Transform)

internal fun readPhotoGeometry(path: String): PhotoGeometry? {
    if (!File(path).isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (ignored: IOException) {
        ExifInterface.ORIENTATION_NORMAL // rovnako ako UprightDecoder: bez čitateľného EXIF sa neotáča
    }
    return PhotoGeometry(bounds.outWidth, bounds.outHeight, RelBox.exifTransform(orientation))
}
