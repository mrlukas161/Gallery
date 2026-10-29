package org.fossify.gallery.faces

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// E2 — formát stĺpcov merge_log (JSON) a plán vrátenia zlúčenia. Čistá logika bez Androidu (MergeUndoTest);
// zápis robí PeopleDao.undoMerge v jednej transakcii, skupiny (extras.db) volajúci.
object MergeLogCodec {
    // priradenie zlúčenej osoby pred zlúčením
    data class Assign(
        val faceId: Long,
        val manual: Boolean,
        val source: String?,
        val confidence: Float?,
        val updatedAt: Long,
    )

    // from = tváre, ktoré „nie sú“ zlúčená osoba; moved = tie z nich, ktoré zlúčenie presunulo na cieľovú osobu
    // (cieľová ich predtým nemala); toRemoved = „nie je“ cieľovej osoby, ktoré zlúčenie zmazalo, lebo tvár
    // zlúčenej osoby jej odvtedy patrí
    data class Links(val from: List<Long>, val moved: List<Long>, val toRemoved: List<Long>)

    // skupiny (extras.db): from = skupiny zlúčenej osoby, toAdded = do ktorých sa cieľová osoba dostala zlúčením
    data class Groups(val from: List<Long>, val toAdded: List<Long>)

    private const val K_FACE = "f"
    private const val K_MANUAL = "m"
    private const val K_SOURCE = "s"
    private const val K_CONFIDENCE = "c"
    private const val K_UPDATED = "u"
    private const val K_FROM = "from"
    private const val K_MOVED = "moved"
    private const val K_TO_REMOVED = "to_removed"
    private const val K_TO_ADDED = "to_added"

    fun encodeAssignments(list: List<FaceAssignmentEntity>): String {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject().put(K_FACE, a.faceId).put(K_MANUAL, a.isManual).put(K_UPDATED, a.updatedAt)
            a.source?.let { o.put(K_SOURCE, it) }
            a.confidence?.let { o.put(K_CONFIDENCE, it.toDouble()) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decodeAssignments(text: String?): List<Assign> {
        val arr = parse(text) { JSONArray(it) } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.takeIf { it.has(K_FACE) }?.let { o ->
                Assign(
                    faceId = o.optLong(K_FACE),
                    manual = o.optBoolean(K_MANUAL, true),
                    source = if (o.has(K_SOURCE) && !o.isNull(K_SOURCE)) o.optString(K_SOURCE) else null,
                    confidence = if (o.has(K_CONFIDENCE)) o.optDouble(K_CONFIDENCE).toFloat() else null,
                    updatedAt = o.optLong(K_UPDATED, 0L),
                )
            }
        }
    }

    fun encodeLinks(links: Links): String = JSONObject()
        .put(K_FROM, JSONArray(links.from))
        .put(K_MOVED, JSONArray(links.moved))
        .put(K_TO_REMOVED, JSONArray(links.toRemoved))
        .toString()

    fun decodeLinks(text: String?): Links {
        val o = parse(text) { JSONObject(it) } ?: return Links(emptyList(), emptyList(), emptyList())
        return Links(longs(o.optJSONArray(K_FROM)), longs(o.optJSONArray(K_MOVED)), longs(o.optJSONArray(K_TO_REMOVED)))
    }

    fun encodeGroups(groups: Groups): String =
        JSONObject().put(K_FROM, JSONArray(groups.from)).put(K_TO_ADDED, JSONArray(groups.toAdded)).toString()

    fun decodeGroups(text: String?): Groups {
        val o = parse(text) { JSONObject(it) } ?: return Groups(emptyList(), emptyList())
        return Groups(longs(o.optJSONArray(K_FROM)), longs(o.optJSONArray(K_TO_ADDED)))
    }

    fun encodeIds(list: List<Long>): String = JSONArray(list).toString()

    fun decodeIds(text: String?): List<Long> = longs(parse(text) { JSONArray(it) })

    private fun longs(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        val out = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, Long.MIN_VALUE)
            if (v != Long.MIN_VALUE) out.add(v)
        }
        return out
    }

    // poškodený alebo chýbajúci text = null (vrátenie potom vráti len to, čo sa prečítať dá)
    private inline fun <T> parse(text: String?, make: (String) -> T): T? = try {
        if (text.isNullOrEmpty()) null else make(text)
    } catch (ignored: JSONException) {
        null
    }
}

object MergeUndo {
    // dlhé zoznamy id po častiach (limit premenných SQLite na starších Androidoch je 999)
    const val ID_CHUNK = 500

    enum class Blocked { ALREADY_UNDONE, PERSON_EXISTS, TARGET_MISSING }

    // aktuálny stav people.db pre jeden záznam: existujúce osoby, tváre teraz priradené cieľovej osobe
    // a jej „nie je“ (face_id)
    data class State(val personIds: Set<Long>, val targetFaces: Set<Long>, val targetLinks: Set<Long>)

    // Čo vrátenie zapíše. Tváre, ktoré medzičasom niekto preradil inam (už nepatria cieľovej osobe), ostanú,
    // kde sú (skipped) — vrátenie nikdy neprepíše novšie rozhodnutie.
    data class Plan(
        val personId: Long,
        val name: String?,
        val createdAt: Long,
        val targetId: Long,
        val assignments: List<FaceAssignmentEntity>,
        val insertFromLinks: List<Long>,
        val deleteTargetLinks: List<Long>,
        val insertTargetLinks: List<Long>,
        val anchorIds: List<Long>,
        val groupsAdd: List<Long>,
        val groupsRemoveTarget: List<Long>,
        val skipped: Int,
    )

    sealed interface Outcome {
        data class Ready(val plan: Plan) : Outcome

        data class Refused(val reason: Blocked) : Outcome
    }

    fun plan(log: MergeLogEntity, state: State): Outcome {
        if (log.undoneAt != null) return Outcome.Refused(Blocked.ALREADY_UNDONE)
        // id osoby je AUTOINCREMENT — znovu sa nepridelí, takže pôvodná osoba sa vráti pod svojím id
        if (log.fromPersonId in state.personIds) return Outcome.Refused(Blocked.PERSON_EXISTS)
        if (log.toPersonId !in state.personIds) return Outcome.Refused(Blocked.TARGET_MISSING)
        val assigns = MergeLogCodec.decodeAssignments(log.faceIds)
        val links = MergeLogCodec.decodeLinks(log.cannotLinks)
        val groups = MergeLogCodec.decodeGroups(log.groupIds)
        val restored = assigns.filter { it.faceId in state.targetFaces }
        val restoredIds = restored.mapTo(HashSet()) { it.faceId }
        val assignments = restored.map {
            FaceAssignmentEntity(it.faceId, log.fromPersonId, it.manual, it.updatedAt, it.source, it.confidence)
        }
        return Outcome.Ready(
            Plan(
                personId = log.fromPersonId,
                name = log.fromName,
                createdAt = log.fromCreatedAt ?: log.createdAt,
                targetId = log.toPersonId,
                assignments = assignments,
                insertFromLinks = links.from.distinct(),
                // „nie je“ presunuté zlúčením patria zas pôvodnej osobe (na cieľovej sa zmažú, ak tam ešte sú)
                deleteTargetLinks = links.moved.filter { it in state.targetLinks },
                // zmazané „nie je“ cieľovej osoby sa vrátia len pri tvárach, ktoré sa naozaj vracajú
                insertTargetLinks = links.toRemoved.filter { it in restoredIds && it !in state.targetLinks },
                anchorIds = MergeLogCodec.decodeIds(log.anchorIds),
                groupsAdd = groups.from,
                groupsRemoveTarget = groups.toAdded,
                skipped = assigns.size - restored.size,
            )
        )
    }
}
