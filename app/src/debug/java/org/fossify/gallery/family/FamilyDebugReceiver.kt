package org.fossify.gallery.family

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.faces.ExtrasDatabase
import org.fossify.gallery.faces.FacesDatabase
import org.fossify.gallery.faces.PeopleDatabase
import org.fossify.gallery.helpers.FaceDecisions

// LEN DEBUG BUILD (src/debug): háčiky pre end-to-end test rodinného prepojenia na dvoch emulátoroch
// (.github/e2e/family.sh). Volá sa cez adb s explicitným komponentom; prijímač chráni povolenie DUMP,
// ktoré má adb shell, ale bežná appka ho dostať nemôže:
//   adb shell am broadcast -n sk.havrila.galeria.debug/org.fossify.gallery.family.FamilyDebugReceiver \
//       --es cmd sync --es id 7
// Každý príkaz robí to isté, čo príslušná akcia v UI (rovnaké DAO + FamilyRecorder), a výsledok zapíše
// do logcatu pod značkou FamilyE2E ako „DONE id=… <výsledok>“.
class FamilyDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val id = intent.getStringExtra("id").orEmpty()
        val pending = goAsync()
        Thread {
            val result = try {
                run(app, cmd, intent)
            } catch (e: Throwable) {
                Log.w(TAG, "príkaz $cmd zlyhal", e)
                "ERROR ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "DONE id=$id cmd=$cmd $result")
            pending.finish()
        }.start()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun run(app: Context, cmd: String, intent: Intent): String {
        val arg = intent.getStringExtra("arg").orEmpty()
        val arg2 = intent.getStringExtra("arg2").orEmpty()
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        return when (cmd) {
            "name" -> {
                FamilyStore(app).setSelfName(arg)
                "name=${FamilyStore(app).selfName()}"
            }
            "manual" -> {
                FamilyPrefs(app).manualEndpoint = arg
                "manual=${FamilyPrefs(app).manualEndpoint}"
            }
            "server_on" -> "port=${FamilyServer.acquire(app, OWNER)}"
            "server_off" -> {
                FamilyServer.release(OWNER)
                "port=${FamilyServer.port()}"
            }
            // odkaz z práve zobrazeného QR (server musí bežať, napr. otvorené „Prepojiť telefón“)
            "pair_link" -> {
                val port = FamilyServer.port() ?: return "NO_SERVER"
                val store = FamilyStore(app)
                val uri = PairUri(store.selfId(), store.selfName(), FamilyServer.pairingTokens.currentOrIssue(), arg, port)
                "link=${uri.toUri()}"
            }
            "sync" -> FamilyAutoSync.exclusive {
                FamilyServer.acquire(app, OWNER_SYNC)
                try {
                    FamilyClient(app).syncAll().joinToString(" ") {
                        "[${it.deviceId.take(8)} ${it.status} recv=${it.opsReceived} sent=${it.opsSent} " +
                            "bundles=${it.bundlesSent}]"
                    }.ifEmpty { "NO_PEERS" }
                } finally {
                    FamilyServer.release(OWNER_SYNC)
                }
            }
            // nová osoba z tvárí fotky (cesta) alebo zo všetkých nepriradených tvárí (bez cesty), ako v UI
            "create_person" -> {
                val faceDao = FacesDatabase.getInstance(app).FaceDao()
                val assigned = people.getAssignments().mapTo(HashSet()) { it.faceId }
                val faces = if (arg2.isNotEmpty()) faceDao.getFacesForPath(arg2) else faceDao.getAllFaces()
                val ids = faces.mapNotNull { it.id }.filter { it !in assigned }
                if (ids.isEmpty() && arg2.isNotEmpty()) return "NO_FACES"
                val personId = FaceDecisions.moveToNewPerson(app, ids, arg)
                "person=$personId faces=${ids.size}"
            }
            // ručné priradenie tváre (arg3 = poradie tváre na fotke arg2) osobe arg, ako presun tváre v UI
            "assign" -> {
                val person = people.getPersons().firstOrNull { it.name == arg } ?: return "NO_PERSON"
                val personId = person.id ?: return "NO_PERSON"
                val index = intent.getStringExtra("arg3")?.toIntOrNull() ?: 0
                val faces = FacesDatabase.getInstance(app).FaceDao().getFacesForPath(arg2)
                val faceId = faces.firstOrNull { it.faceIndex == index }?.id ?: return "NO_FACE faces=${faces.size}"
                FaceDecisions.move(app, listOf(faceId), personId)
                "assigned=$faceId"
            }
            "rename" -> {
                val person = people.getPersons().firstOrNull { it.name == arg } ?: return "NO_PERSON"
                val personId = person.id ?: return "NO_PERSON"
                people.renamePerson(personId, arg2)
                FamilyRecorder.personRenamed(app, personId, person.name, arg2)
                "renamed=$personId"
            }
            "delete" -> {
                val person = people.getPersons().firstOrNull { it.name == arg } ?: return "NO_PERSON"
                val personId = person.id ?: return "NO_PERSON"
                people.deletePersonsCompletely(listOf(personId))
                FamilyRecorder.personsDeleted(app, mapOf(personId to person.name))
                ExtrasDatabase.getInstance(app).ExtrasDao().removePersonFromGroups(personId)
                "deleted=$personId"
            }
            // „Poslať do prepojeného telefónu“ (arg2 = "results" → len výsledky), ako FamilySendUi
            "send" -> {
                val peer = FamilyStore(app).peers().firstOrNull() ?: return "NO_PEER"
                val queued = FamilySend.enqueue(app, listOf(arg), peer, arg2 != "results")
                val result = if (queued > 0) FamilySend.syncNow(app, peer.deviceId) else null
                "queued=$queued status=${result?.status} bundles=${result?.bundlesSent}"
            }
            "dump" -> dump(app)
            else -> "UNKNOWN"
        }
    }

    private fun dump(app: Context): String {
        val people = PeopleDatabase.getInstance(app).PeopleDao()
        val family = FamilyDatabase.getInstance(app)
        val faceDao = FacesDatabase.getInstance(app).FaceDao()
        val assignments = people.getAssignments().groupBy { it.personId }
        val persons = people.getPersons().joinToString(",") { "${it.id}:${it.name}:${assignments[it.id]?.size ?: 0}" }
        val peers = family.peerDao().getPeers().joinToString(",") { "${it.name}@${it.lastLamportSeen}" }
        val proposals = family.proposalDao().getProposals(ProposalEntity.STATE_OPEN).joinToString(",") { it.type }
        val faces = faceDao.getAllFaces().size
        val processed = faceDao.getProcessedPaths().size
        val pendingOut = family.outboxDao().countOutbox(OutboxEntity.STATE_PENDING)
        return "self=${FamilyStore(app).selfName()} peers=[$peers] persons=[$persons] proposals=[$proposals] " +
            "faces=$faces processed=$processed outbox=$pendingOut unpairPending=${FamilyPrefs(app).pendingUnpairs().size}"
    }

    private companion object {
        const val TAG = "FamilyE2E"
        const val OWNER = "e2e"
        const val OWNER_SYNC = "e2e-sync"
    }
}
