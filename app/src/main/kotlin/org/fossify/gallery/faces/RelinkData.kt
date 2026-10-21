package org.fossify.gallery.faces

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.family.ContentKeyStore
import org.fossify.gallery.family.FamilyDatabase
import org.fossify.gallery.helpers.IndexPerf
import org.json.JSONObject
import java.io.File
import java.io.IOException

// A5: čítanie podkladov pre FaceRelinker (MediaStore, odkazy na tváre, obsahové kľúče, rozmery fotky).
internal object RelinkData {
    // Candidate.mediaStoreId pre skrytú dvojičku, ktorú MediaStore nepozná: ponechať pôvodné id
    const val KEEP_STORE_ID = -1L

    // koľko kľúčov sa najviac dopočíta za jeden beh bez nabíjačky (číta ~128 kB na fotku)
    private const val PRECOMPUTE_BUDGET = 300

    // kľúč v payload_json návrhov FaceMove/FaceRemove (family.OpCodec.FACE_ID)
    private const val PROPOSAL_FACE_ID = "face_id"

    // Id tvárí, na ktoré niečo odkazuje: priradenia a „nie je to on" (people.db), odložené (extras.db)
    // a návrhy z druhého telefónu (family.db proposals, pole face_id). null = nedá sa zistiť → nič nemazať
    // (aj jediný nečitateľný návrh môže odkazovať na čokoľvek).
    fun loadReferencedIds(app: Context): Set<Long>? = try {
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val set = HashSet<Long>()
        people.getAssignments().mapTo(set) { it.faceId }
        people.getCannotLinks().mapTo(set) { it.faceId }
        set.addAll(ExtrasDatabase.getInstance(app).ExtrasDao().getIgnoredIds())
        val payloads = FamilyDatabase.getInstance(app).openHelper.readableDatabase
            .query("SELECT payload_json FROM proposals").use { c -> readStrings(c) }
        payloads.map { JSONObject(it).optLong(PROPOSAL_FACE_ID, -1L) }.filterTo(set) { it >= 0L }
        set
    } catch (ignored: Throwable) {
        null
    }

    // (MediaStore id, cesta) obrázkov, ktorých veľkosť je v `sizes`
    fun queryImagesWithSize(app: Context, sizes: Set<Long>): List<Pair<Long, String>> = try {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
        )
        app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
            ?.use { c -> readImages(c, sizes) }
            .orEmpty()
    } catch (ignored: Throwable) {
        emptyList()
    }

    private fun readImages(c: Cursor, sizes: Set<Long>): List<Pair<Long, String>> {
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val list = ArrayList<Pair<Long, String>>()
        while (c.moveToNext()) {
            val path = c.getString(dataCol)
            // MediaStore môže mať zastaranú veľkosť — rozhodne až File.length() vo FaceRelinker
            val sizeOk = c.isNull(sizeCol) || c.getLong(sizeCol) in sizes
            if (path != null && sizeOk) list.add(c.getLong(idCol) to path)
        }
        return list
    }

    private fun readStrings(c: Cursor): List<String> {
        val list = ArrayList<String>()
        while (c.moveToNext()) {
            c.getString(0)?.let { list.add(it) }
        }
        return list
    }

    // „.meno" ↔ „meno" v tom istom priečinku (skrytie/odkrytie premenovaním)
    fun hiddenTwin(path: String): String? {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val twinName = if (name.startsWith(".")) name.drop(1) else ".$name"
        return if (dir.isEmpty() || name.isEmpty() || twinName.isEmpty()) null else "$dir/$twinName"
    }

    // [surová šírka, surová výška, EXIF orientácia] fotky, alebo null
    fun rawGeometry(path: String): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (ignored: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return intArrayOf(bounds.outWidth, bounds.outHeight, orientation)
    }

    // Dopočíta chýbajúce/zastarané kľúče ciest s tvárami, aby ich mal relinker po presune k dispozícii.
    // Pri nabíjaní všetky, inak najviac PRECOMPUTE_BUDGET za beh (postupne sa doplní celá knižnica).
    // Chýbajúcej ceste sa uložený kľúč NEMAŽE — práve ten relinker potrebuje.
    fun precomputeKeys(app: Context, keys: ContentKeyStore, paths: List<String>, running: () -> Boolean) {
        val known = HashMap<String, Pair<Long, Long>>()
        FamilyDatabase.getInstance(app).openHelper.readableDatabase
            .query("SELECT path, size, mtime FROM content_keys").use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { known[it] = c.getLong(1) to c.getLong(2) }
                }
            }
        val budget = if (IndexPerf.battery(app).charging) Int.MAX_VALUE else PRECOMPUTE_BUDGET
        paths.asSequence()
            .filter { path ->
                val f = File(path)
                f.isFile && known[path] != (f.length() to f.lastModified())
            }
            .take(budget)
            .takeWhile { running() }
            .forEach { keys.keyFor(it) }
    }
}
