package org.fossify.gallery.family

import org.json.JSONArray
import org.json.JSONObject

// Obnova doplnených častí zálohy osôb (A3): automatické priradenia, „toto nie je táto osoba“
// a ignorované tváre. Čistá logika bez Androidu (PeopleBackupExtrasTest).
//
// Rovnaké pravidlo ako pri op-logu: len dopĺňa, nič neprepisuje ani nemaže.
//  - automatické priradenie sa zapíše len tvári, ktorá nemá žiadne priradenie, nie je ignorovaná
//    a pre danú osobu nemá „nie je to on“;
//  - „nie je to on“ sa zapíše len vtedy, keď tvár tej osobe práve nepatrí (ani automaticky);
//  - ignorovanie len tvári bez priradenia.
// Tvár, ktorú tu (zatiaľ) nevidno, alebo osoba, ktorá tu nie je, sa vynechá. Obnova sa dá
// zopakovať po spracovaní knižnice, opakovanie nič nezdvojí.
interface ExtrasTarget {
    fun findFace(ref: FaceRef): Long?

    fun localPersonId(uuid: String): Long?

    fun assignment(faceId: Long): LocalAssignment?

    fun isCannot(faceId: Long, personId: Long): Boolean

    fun isIgnored(faceId: Long): Boolean
}

sealed interface ExtrasAction {
    // E2: confidence = istota zo zálohy (nepovinná); zapisuje sa s pôvodom „restore“
    data class AutoAssign(val faceId: Long, val personId: Long, val confidence: Float? = null) : ExtrasAction

    data class CannotLink(val faceId: Long, val personId: Long) : ExtrasAction

    data class Ignore(val faceId: Long) : ExtrasAction
}

data class ExtrasReport(
    val auto: Int = 0,
    val cannot: Int = 0,
    val ignored: Int = 0,
    val missing: Int = 0, // tvár alebo osoba tu nie je
    val kept: Int = 0, // už bolo, alebo by to prepísalo existujúce rozhodnutie
)

object PeopleBackupExtras {

    fun plan(extras: PeopleBackupFormat.Extras, target: ExtrasTarget): Pair<List<ExtrasAction>, ExtrasReport> {
        val planner = Planner(target)
        // najprv „nie je to on“ a ignorovanie, aby ich automatické priradenie nepredbehlo
        extras.cannot.forEach { planner.cannot(it) }
        extras.ignored.forEach { planner.ignore(it) }
        extras.auto.forEach { planner.auto(it) }
        return planner.actions to planner.report
    }

    private class Planner(private val target: ExtrasTarget) {
        val actions = ArrayList<ExtrasAction>()
        var report = ExtrasReport()
        private val assigned = HashSet<Long>() // tváre priradené v tomto pláne
        private val cannot = HashSet<Pair<Long, Long>>()
        private val ignored = HashSet<Long>()

        private fun missing() {
            report = report.copy(missing = report.missing + 1)
        }

        private fun kept() {
            report = report.copy(kept = report.kept + 1)
        }

        fun cannot(link: PeopleBackupFormat.FaceLink) {
            val face = target.findFace(link.face)
            val person = target.localPersonId(link.uuid)
            when {
                face == null || person == null -> missing()
                target.assignment(face)?.personId == person -> kept()
                target.isCannot(face, person) || !cannot.add(face to person) -> kept()
                else -> {
                    actions.add(ExtrasAction.CannotLink(face, person))
                    report = report.copy(cannot = report.cannot + 1)
                }
            }
        }

        fun ignore(ref: FaceRef) {
            val face = target.findFace(ref)
            when {
                face == null -> missing()
                target.assignment(face) != null || target.isIgnored(face) || !ignored.add(face) -> kept()
                else -> {
                    actions.add(ExtrasAction.Ignore(face))
                    report = report.copy(ignored = report.ignored + 1)
                }
            }
        }

        fun auto(link: PeopleBackupFormat.FaceLink) {
            val face = target.findFace(link.face)
            val person = target.localPersonId(link.uuid)
            when {
                face == null || person == null -> missing()
                target.assignment(face) != null || face in assigned -> kept()
                target.isIgnored(face) || face in ignored -> kept()
                target.isCannot(face, person) || (face to person) in cannot -> kept()
                else -> {
                    assigned.add(face)
                    actions.add(ExtrasAction.AutoAssign(face, person, link.confidence))
                    report = report.copy(auto = report.auto + 1)
                }
            }
        }
    }
}

// Doplnené kľúče súboru zálohy (PeopleBackupFormat, A3). Tvár má rovnaký tvar ako v op-logu
// (OpCodec: "ck" + "boxes" [[l, t, r, b], …]). Poškodená položka sa vynechá, ostatné sa prečítajú.
internal object PeopleBackupExtrasJson {
    private const val K_AUTO = "auto_assign"
    private const val K_CANNOT = "cannot_links"
    private const val K_IGNORED = "ignored_faces"
    private const val K_CK = "ck"
    private const val K_BOXES = "boxes"
    private const val K_UUID = "uuid"
    private const val K_SOURCE = "src"
    private const val K_CONFIDENCE = "conf"
    private const val BOX_VALUES = 4
    private const val LEFT = 0
    private const val TOP = 1
    private const val RIGHT = 2
    private const val BOTTOM = 3

    fun put(extras: PeopleBackupFormat.Extras, o: JSONObject) {
        if (extras.auto.isNotEmpty()) o.put(K_AUTO, JSONArray(extras.auto.map { linkJson(it) }))
        if (extras.cannot.isNotEmpty()) o.put(K_CANNOT, JSONArray(extras.cannot.map { linkJson(it) }))
        if (extras.ignored.isNotEmpty()) o.put(K_IGNORED, JSONArray(extras.ignored.map { faceJson(it) }))
    }

    fun read(o: JSONObject) = PeopleBackupFormat.Extras(
        auto = links(o.optJSONArray(K_AUTO)),
        cannot = links(o.optJSONArray(K_CANNOT)),
        ignored = items(o.optJSONArray(K_IGNORED)) { face(it) },
    )

    private fun faceJson(f: FaceRef): JSONObject {
        val boxes = f.boxes.map { b -> JSONArray(listOf(b.left, b.top, b.right, b.bottom).map(Float::toDouble)) }
        return JSONObject().put(K_CK, f.contentKey).put(K_BOXES, JSONArray(boxes))
    }

    private fun linkJson(l: PeopleBackupFormat.FaceLink): JSONObject {
        val o = faceJson(l.face).put(K_UUID, l.uuid)
        l.source?.let { o.put(K_SOURCE, it) }
        l.confidence?.let { o.put(K_CONFIDENCE, it.toDouble()) }
        return o
    }

    private fun face(o: JSONObject): FaceRef? {
        val key = o.optString(K_CK)
        val arr = o.optJSONArray(K_BOXES)
        if (key.isEmpty() || arr == null || arr.length() == 0) return null
        val boxes = (0 until arr.length()).map { i -> box(arr.optJSONArray(i)) }
        return if (boxes.any { it == null }) null else FaceRef(key, boxes.filterNotNull())
    }

    private fun box(b: JSONArray?): RelBox? {
        if (b == null || b.length() != BOX_VALUES) return null
        val v = (0 until BOX_VALUES).map { b.optDouble(it).toFloat() }
        return if (v.any { it.isNaN() }) null else RelBox(v[LEFT], v[TOP], v[RIGHT], v[BOTTOM])
    }

    private fun links(arr: JSONArray?): List<PeopleBackupFormat.FaceLink> = items(arr) { o ->
        val uuid = o.optString(K_UUID)
        val source = if (o.has(K_SOURCE) && !o.isNull(K_SOURCE)) o.optString(K_SOURCE) else null
        val confidence = o.optDouble(K_CONFIDENCE).takeIf { o.has(K_CONFIDENCE) && !it.isNaN() }?.toFloat()
        if (uuid.isEmpty()) null else face(o)?.let { PeopleBackupFormat.FaceLink(it, uuid, source, confidence) }
    }

    private fun <T> items(arr: JSONArray?, parse: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(parse) }
    }
}
