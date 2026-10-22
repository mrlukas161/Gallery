package org.fossify.gallery.family

import org.fossify.gallery.faces.FaceAssignmentEntity

// D6: pôvod priradenia tváre z prepojeného telefónu (remote_assignment). Priradenie je „cudzie“, kým sa
// lokálne nezmenilo: rovnaká osoba aj čas zápisu (FaceAssignmentEntity.updatedAt). Každé lokálne potvrdenie,
// preradenie alebo odobratie zapíše nový čas, a tým sa priradenie stane vlastným. Čistá logika (RemoteOriginTest).
object RemoteOrigin {
    fun record(entity: FaceAssignmentEntity, deviceId: String, opId: String) =
        RemoteAssignmentEntity(entity.faceId, deviceId, opId, entity.personId, entity.updatedAt)

    fun untouched(row: RemoteAssignmentEntity, current: FaceAssignmentEntity?): Boolean =
        current != null && untouched(row, current.personId, current.updatedAt)

    fun untouched(row: RemoteAssignmentEntity, personId: Long, updatedAt: Long): Boolean =
        row.personId == personId && row.updatedAt == updatedAt

    // tváre, ktorých priradenie prišlo z rodiny a nikto sa ho tu nedotkol (AnchorOutbox ich neposiela ako vlastné)
    fun foreignFaces(rows: List<RemoteAssignmentEntity>, current: Map<Long, FaceAssignmentEntity>): Set<Long> =
        rows.filter { untouched(it, current[it.faceId]) }.mapTo(HashSet()) { it.faceId }

    // značka pôvodu pre priradenia z balíka k fotke (nie z op-logu)
    fun bundleOpId(inboxId: String) = "bundle:$inboxId"
}
