package org.fossify.gallery.family

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.faces.FaceDao
import org.fossify.gallery.faces.FaceEntity
import org.fossify.gallery.faces.IndexScope
import java.io.File
import java.io.IOException

// Prevod lokálnej tváre (faces.db) na FaceRef (obsahový kľúč + relatívny rámček) a späť.
// Volá sa len z pozadia: číta hlavičky súborov, EXIF a počíta obsahové kľúče.
// Jedna inštancia na jeden beh (cache rozmerov fotiek), nie je thread-safe.
class FamilyFaces(context: Context, private val faceDao: FaceDao, private val keys: ContentKeyStore) {
    private val scope = IndexScope(context)
    private val geometry = HashMap<String, Geometry?>()

    private data class Geometry(val rawWidth: Int, val rawHeight: Int, val transform: RelBox.Transform)

    // null = tvár z nepovoleného priečinka (zamknutý/vylúčený, spec 2.5) alebo fotka sa nedá prečítať
    fun refFor(face: FaceEntity): FaceRef? {
        val path = face.mediaFullPath
        if (!scope.allows(path)) return null
        val boxes = boxesFor(face) ?: return null
        val key = keys.keyFor(path) ?: return null
        return FaceRef(key, boxes)
    }

    // lokálne id tváre zodpovedajúcej FaceRef, null = fotka s týmto obsahom tu (zatiaľ) nie je
    fun findFace(ref: FaceRef): Long? {
        for (path in keys.pathsFor(ref.contentKey)) {
            // uložený kľúč mohol zastarať (súbor sa zmenil) — overí sa podľa veľkosti a mtime
            if (keys.keyFor(path) != ref.contentKey) continue
            val candidates = faceDao.getFacesForPath(path).mapNotNull { f ->
                val id = f.id ?: return@mapNotNull null
                boxesFor(f)?.let { id to it }
            }
            FaceMatch.best(ref.boxes, candidates)?.let { return it }
        }
        return null
    }

    private fun boxesFor(face: FaceEntity): List<RelBox>? {
        val g = geometry.getOrPut(face.mediaFullPath) { readGeometry(face.mediaFullPath) } ?: return null
        return FaceMatch.relBoxes(
            face.bboxLeft, face.bboxTop, face.bboxRight, face.bboxBottom,
            g.rawWidth, g.rawHeight, g.transform,
        ).takeIf { it.isNotEmpty() }
    }

    private fun readGeometry(path: String): Geometry? {
        if (!File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (ignored: IOException) {
            ExifInterface.ORIENTATION_NORMAL // rovnako ako UprightDecoder: bez čitateľného EXIF sa neotáča
        }
        return Geometry(bounds.outWidth, bounds.outHeight, RelBox.exifTransform(orientation))
    }
}
