package org.fossify.gallery.family

// Op-log rodinného prepojenia (spec 2.2, 2.4). Prenášajú sa ZMENY (operácie), nie stav.
// Osoba sa adresuje rodinným UUID (mapa na lokálne id je vo family_persons), tvár obsahovým
// kľúčom fotky + relatívnym rámčekom (FaceRef), nikdy lokálnou cestou ani face id.
// Čistá logika bez Androidu; JSON je v OpCodec, rozhodovanie v OpApplier.

// Typy operácií = hodnota stĺpca ops.type. Nové typy len pridávať, existujúce nikdy nepremenovať
// (staršia appka neznámy typ odloží a aplikuje ho až po aktualizácii).
object OpType {
    const val PERSON_CREATE = "person_create"
    const val PERSON_RENAME = "person_rename"
    const val PERSON_MERGE = "person_merge"
    const val PERSON_DELETE = "person_delete"
    const val ASSIGN_FACE = "assign_face"
    const val UNASSIGN_FACE = "unassign_face"
    const val GROUP_SET = "group_set"
    const val GROUP_RENAME = "group_rename"
    const val ANCHORS_SET = "anchors_set"
}

// Tvár na fotke: obsahový kľúč (ContentKey) + rámček v relatívnych upright súradniciach.
// boxes má 1 prvok, ak je priestor rámčeka istý; 2 prvky (upright a surová hypotéza), ak fotka
// má EXIF otočenie a starý rámček mohol byť uložený v neotočenom priestore (pozri FaceMatch).
data class FaceRef(val contentKey: String, val boxes: List<RelBox>)

sealed interface Op {
    val type: String
}

// Nová osoba (aj prvé zverejnenie existujúcej lokálnej osoby). name = null pre nepomenovanú.
data class PersonCreate(val uuid: String, val name: String?) : Op {
    override val type: String get() = OpType.PERSON_CREATE
}

// Premenovanie. previousName = meno, ktoré mal odosielateľ PRED zmenou: keď ho má aj prijímateľ,
// premenovanie sa prevezme potichu; keď prijímateľ medzitým premenoval inak, vznikne návrh.
data class PersonRename(val uuid: String, val name: String, val previousName: String?) : Op {
    override val type: String get() = OpType.PERSON_RENAME
}

// Zlúčenie osoby fromUuid do toUuid. Na druhej strane je to vždy len návrh.
data class PersonMerge(val fromUuid: String, val toUuid: String) : Op {
    override val type: String get() = OpType.PERSON_MERGE
}

// Zmazanie osoby. NIKDY sa neprenáša ako zmazanie, na druhej strane je z neho len návrh.
data class PersonDelete(val uuid: String, val name: String?) : Op {
    override val type: String get() = OpType.PERSON_DELETE
}

// Priradenie tváre osobe; manual = používateľ ho potvrdil ručne (is_manual). E2: source = pôvod priradenia
// u odosielateľa (AssignmentSource, len informácia; nepovinné — staršia appka ho neposiela ani nečíta).
// Prijímateľ si zapíše vlastný pôvod („family“, pri obnove zálohy „restore“).
data class AssignFace(val face: FaceRef, val uuid: String, val manual: Boolean, val source: String? = null) : Op {
    override val type: String get() = OpType.ASSIGN_FACE
}

// „Toto nie je táto osoba“: odobratie tváre osobe.
data class UnassignFace(val face: FaceRef, val uuid: String) : Op {
    override val type: String get() = OpType.UNASSIGN_FACE
}

// Skupina osôb (podľa názvu) a jej členovia. Prijímateľ členov len pridáva, nikdy neodoberá.
data class GroupSet(val name: String, val memberUuids: List<String>) : Op {
    override val type: String get() = OpType.GROUP_SET
}

data class GroupRename(val oldName: String, val newName: String) : Op {
    override val type: String get() = OpType.GROUP_RENAME
}

// Do MAX_ANCHORS reprezentatívnych odtlačkov osoby. faceModelId = verzia embeddera; pri inom modeli
// prijímateľ odtlačky zahodí (vektory rôznych modelov sa nikdy nemiešajú).
data class AnchorsSet(val uuid: String, val faceModelId: String, val vectors: List<FloatArray>) : Op {
    override val type: String get() = OpType.ANCHORS_SET

    companion object {
        const val MAX_ANCHORS = 20
    }
}

// Operácia s hlavičkou op-logu: (deviceId, lamport, opId) dáva deterministické poradie a idempotenciu.
data class LoggedOp(val opId: String, val deviceId: String, val lamport: Long, val op: Op) {
    companion object {
        // Rovnaké poradie na oboch telefónoch: lamport, potom deviceId, potom opId.
        val ORDER: Comparator<LoggedOp> =
            compareBy<LoggedOp> { it.lamport }.thenBy { it.deviceId }.thenBy { it.opId }
    }
}
