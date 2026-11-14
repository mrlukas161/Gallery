package org.fossify.gallery.family

import android.content.Context
import android.util.Log
import org.fossify.gallery.helpers.IndexPerf
import org.fossify.gallery.jobs.FamilySyncJob
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// Spúšťače synchronizácie (F5 bod 3, spec Časť 1 „Kedy“):
//  (a) otvorenie appky (onResume hlavnej obrazovky): krátky pokus syncAll (≈ 5 s hľadanie telefónu), len keď je
//      telefón v lokálnej sieti (Wi-Fi alebo hotspot); pri čakajúcej fronte a neúspechu sa interval predlžuje;
//  (b) okno pri nabíjaní: FamilySyncJob (plánuje sa tu pri štarte appky a vo FamilyStore po spárovaní);
//  (c) druhý telefón práve dokončil synchronizáciu s mojím serverom (/f/ack): ak mám pre neho niečo vo fronte,
//      hneď sa k nemu pripojím ako klient (jeho server ešte chvíľu beží, FamilyServer.release) — inak by moja
//      fronta čakala, kým sa trafí, že som klientom ja;
//  (d) dobehol snapshot Ľudí po spárovaní (FamilySnapshot).
// Naraz beží najviac jedna synchronizácia (zámok), ďalší pokus sa vtedy preskočí.
// Nič tu nebeží trvalo: vlákno pracuje len počas pokusu, server po ňom len krátko dobehne.
@Suppress("TooManyFunctions")
object FamilyAutoSync {
    const val OWNER_RESUME = "resume"
    const val OWNER_WINDOW = "window"
    const val OWNER_WINDOW_CLIENT = "window-client"
    private const val OWNER_REPLY = "reply"

    // hľadanie telefónu v okne pri nabíjaní (opakuje sa každé ~2 min, môže byť dlhšie ako pri otvorení)
    const val WINDOW_TIMEOUT_MS = 10_000L

    // (c): ako dlho počkať, kým dobehne iná synchronizácia, než sa pripojím späť
    private const val REPLY_WAIT_MS = 20_000L

    private const val TAG = "FamilyAutoSync"
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val syncLock = ReentrantLock()

    // rozhrania lokálnej siete (Wi-Fi, hotspot, ethernet, USB/Bluetooth zdieľanie); mobilné dáta (rmnet, ccmni…) nie
    private val LOCAL_IFACES = listOf("wlan", "swlan", "ap", "softap", "wifi", "eth", "rndis", "usb", "bt-pan", "p2p")

    @Volatile
    private var lastResumeAttempt = 0L

    @Volatile
    private var lastRetryPending = 0L

    // MainActivity.onCreate: naplánuje (alebo zruší) okno pri nabíjaní
    fun onAppStarted(context: Context) = background(context) { FamilySyncJob.refresh(it) }

    // MainActivity.onResume: krátky pokus o synchronizáciu, ak je s kým — najviac raz za 15 min,
    // pri čakajúcej fronte skôr (FamilySchedule.shouldTryOnResume, s predlžovaním po neúspechu)
    fun onAppResumed(context: Context) = background(context) { app ->
        retryPendingIfDue(app)
        val battery = IndexPerf.battery(app)
        val now = System.currentTimeMillis()
        val partners = hasPartners(app)
        val pending = partners && outboxPending(app)
        val prefs = FamilyPrefs(app)
        val due = FamilySchedule.shouldTryOnResume(
            partners, battery.charging, battery.percent, lastResumeAttempt, now, pending, prefs.resumeFailures,
        )
        // mimo domácej siete (mobilné dáta) sa telefón ani nehľadá, server sa nespúšťa
        if (due && hasLocalNetwork()) {
            lastResumeAttempt = now
            val ok = syncNow(app, OWNER_RESUME, FamilySchedule.RESUME_TIMEOUT_MS)
            prefs.resumeFailures = if (ok) 0 else prefs.resumeFailures + 1
        }
    }

    // (c) volá FamilyServer po /f/ack druhého telefónu (vlákno servera → práca ide na pozadie)
    fun afterServedSync(context: Context, peerId: String) = background(context) { app ->
        val waiting = try {
            FamilyDatabase.getInstance(app).outboxDao().countOutboxForPeer(peerId, OutboxEntity.STATE_PENDING) > 0
        } catch (ignored: RuntimeException) {
            false
        }
        if (waiting) syncNow(app, OWNER_REPLY, FamilySchedule.RESUME_TIMEOUT_MS, waitMs = REPLY_WAIT_MS)
    }

    // (d) krátky pokus hneď (napr. po snapshote Ľudí), ak je s kým a sme v lokálnej sieti
    fun syncSoon(context: Context) = background(context) { app ->
        if (hasPartners(app) && hasLocalNetwork()) syncNow(app, OWNER_RESUME, FamilySchedule.RESUME_TIMEOUT_MS)
    }

    // čaká niečo na odoslanie? (fotky vo fronte, zrušené prepojenie) Pri chybe DB „nie“ — pokus ide podľa 15 min.
    @Suppress("TooGenericExceptionCaught") // DB rodinného prepojenia nesmie zhodiť návrat do appky
    private fun outboxPending(context: Context): Boolean = try {
        FamilyStore(context).prefs.pendingUnpairs().isNotEmpty() ||
            FamilyDatabase.getInstance(context).outboxDao().countOutbox(OutboxEntity.STATE_PENDING) > 0
    } catch (ignored: RuntimeException) {
        false
    }

    // spárovaný telefón alebo zrušené prepojenie, o ktorom sa druhý telefón ešte nedozvedel
    fun hasPartners(context: Context): Boolean {
        val store = FamilyStore(context)
        return store.peers().isNotEmpty() || store.prefs.pendingUnpairs().isNotEmpty()
    }

    // Je telefón v lokálnej sieti (Wi-Fi klient, vlastný hotspot, kábel)? Rozhrania mobilných dát sa nerátajú,
    // aj keď mávajú adresy 10.x.
    fun hasLocalNetwork(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
            val name = nif.name.orEmpty().lowercase()
            nif.isUp && !nif.isLoopback && LOCAL_IFACES.any { name.startsWith(it) } &&
                nif.inetAddresses.toList().any { LocalNet.isLocal(it.hostAddress.orEmpty()) }
        }
    } catch (ignored: SocketException) {
        true // radšej skúsiť, než sa nikdy nesynchronizovať
    }

    // Jedna synchronizácia so všetkými telefónmi; počas nej server prijíma spojenia. Blokuje, len z pozadia.
    // true = so všetkými telefónmi prebehla celá a nič nečaká vo fronte (aj keď nie je spárovaný žiadny);
    // false = nedostupné, chyba, fronta ešte nie je prázdna, alebo práve beží iná synchronizácia (waitMs = koľko
    // na ňu počkať).
    @Suppress("TooGenericExceptionCaught")
    fun syncNow(context: Context, owner: String, timeoutMs: Long, waitMs: Long = 0): Boolean {
        val locked = try {
            if (waitMs > 0) syncLock.tryLock(waitMs, TimeUnit.MILLISECONDS) else syncLock.tryLock()
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) return false
        val app = context.applicationContext
        return try {
            FamilyServer.acquire(app, owner)
            try {
                val results = FamilyClient(app).syncAll(timeoutMs)
                // telefón bez kľúča (treba znova spárovať) alebo s nezlučiteľnou verziou nie je chyba synchronizácie,
                // len sa preskočí
                val done = setOf(
                    FamilyClient.Status.OK,
                    FamilyClient.Status.UNPAIRED,
                    FamilyClient.Status.NEEDS_PAIRING,
                    FamilyClient.Status.INCOMPATIBLE,
                )
                results.all { it.status in done && (it.status != FamilyClient.Status.OK || it.outboxLeft == 0) } &&
                    FamilyStore(app).prefs.pendingUnpairs().isEmpty()
            } finally {
                FamilyServer.release(owner)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "synchronizácia zlyhala", e)
            false
        } finally {
            syncLock.unlock()
        }
    }

    // Odložené operácie (napr. z obnovy zálohy, kým fotky ešte nemajú tváre) sa skúsia znova,
    // najviac raz za FamilySchedule.RETRY_PENDING_INTERVAL_MS. Len z pozadia.
    @Suppress("TooGenericExceptionCaught")
    fun retryPendingIfDue(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRetryPending < FamilySchedule.RETRY_PENDING_INTERVAL_MS) return
        if (!syncLock.tryLock()) return // práve sa synchronizuje, operácie aplikuje príjem
        lastRetryPending = now
        try {
            val sync = FamilySync(context)
            if (sync.hasPendingOps()) sync.retryPending()
        } catch (e: RuntimeException) {
            Log.w(TAG, "odložené operácie zlyhali", e)
        } finally {
            syncLock.unlock()
        }
    }

    // Aplikovanie operácií mimo synchronizácie (obnova zálohy osôb) počká, kým synchronizácia dobehne.
    // Samotné aplikovanie chráni ešte FamilyLock (jeden vlastník aj pre vlákno servera). Len z pozadia.
    fun <T> exclusive(block: () -> T): T {
        syncLock.lock()
        try {
            return block()
        } finally {
            syncLock.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun background(context: Context, block: (Context) -> Unit) {
        val app = context.applicationContext
        try {
            worker.execute {
                try {
                    block(app)
                } catch (e: RuntimeException) {
                    // rodinné prepojenie je vedľajšie, appku nesmie zhodiť
                    Log.w(TAG, "spúšťač zlyhal", e)
                }
            }
        } catch (ignored: RejectedExecutionException) {
        }
    }
}
