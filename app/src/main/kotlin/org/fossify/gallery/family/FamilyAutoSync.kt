package org.fossify.gallery.family

import android.content.Context
import android.util.Log
import org.fossify.gallery.helpers.IndexPerf
import org.fossify.gallery.jobs.FamilySyncJob
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock

// Spúšťače synchronizácie (F5 bod 3, spec Časť 1 „Kedy“):
//  (a) otvorenie appky (onResume hlavnej obrazovky): krátky pokus syncAll (≈ 5 s hľadanie telefónu),
//      počas neho je server prijímateľný (FamilyServer.acquire/release);
//  (b) okno pri nabíjaní: FamilySyncJob (plánuje sa tu pri štarte appky a vo FamilyStore po spárovaní).
// Naraz beží najviac jedna synchronizácia (zámok), ďalší pokus sa vtedy preskočí.
// Nič tu nebeží trvalo: vlákno pracuje len počas pokusu, server sa po ňom hneď uvoľní.
object FamilyAutoSync {
    const val OWNER_RESUME = "resume"
    const val OWNER_WINDOW = "window"
    const val OWNER_WINDOW_CLIENT = "window-client"

    // hľadanie telefónu v okne pri nabíjaní (opakuje sa každé ~2 min, môže byť dlhšie ako pri otvorení)
    const val WINDOW_TIMEOUT_MS = 10_000L

    private const val TAG = "FamilyAutoSync"
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val syncLock = ReentrantLock()

    @Volatile
    private var lastResumeAttempt = 0L

    @Volatile
    private var lastRetryPending = 0L

    // MainActivity.onCreate: naplánuje (alebo zruší) okno pri nabíjaní
    fun onAppStarted(context: Context) = background(context) { FamilySyncJob.refresh(it) }

    // MainActivity.onResume: krátky pokus o synchronizáciu, ak je s kým — najviac raz za 15 min,
    // pri čakajúcej fronte hneď (FamilySchedule.shouldTryOnResume)
    fun onAppResumed(context: Context) = background(context) { app ->
        retryPendingIfDue(app)
        val battery = IndexPerf.battery(app)
        val now = System.currentTimeMillis()
        val partners = hasPartners(app)
        val pending = partners && outboxPending(app)
        val due = FamilySchedule.shouldTryOnResume(
            partners, battery.charging, battery.percent, lastResumeAttempt, now, pending,
        )
        if (due) {
            lastResumeAttempt = now
            syncNow(app, OWNER_RESUME, FamilySchedule.RESUME_TIMEOUT_MS)
        }
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

    // Jedna synchronizácia so všetkými telefónmi; počas nej server prijíma spojenia. Blokuje, len z pozadia.
    // true = so všetkými telefónmi prebehla celá (aj keď nie je spárovaný žiadny); false = nedostupné,
    // chyba alebo práve beží iná synchronizácia.
    @Suppress("TooGenericExceptionCaught")
    fun syncNow(context: Context, owner: String, timeoutMs: Long): Boolean {
        if (!syncLock.tryLock()) return false
        val app = context.applicationContext
        return try {
            FamilyServer.acquire(app, owner)
            try {
                val results = FamilyClient(app).syncAll(timeoutMs)
                // telefón bez kľúča (treba znova spárovať) nie je chyba synchronizácie, len sa preskočí
                val done = setOf(
                    FamilyClient.Status.OK,
                    FamilyClient.Status.UNPAIRED,
                    FamilyClient.Status.NEEDS_PAIRING,
                )
                results.all { it.status in done } && FamilyStore(app).prefs.pendingUnpairs().isEmpty()
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

    // Aplikovanie operácií mimo synchronizácie (obnova zálohy osôb) počká, kým synchronizácia dobehne,
    // aby sa tie isté operácie neaplikovali z dvoch vlákien naraz. Len z pozadia.
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
