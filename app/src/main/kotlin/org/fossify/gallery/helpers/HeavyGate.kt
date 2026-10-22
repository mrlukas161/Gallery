package org.fossify.gallery.helpers

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Najviac JEDNA ťažká úloha (modely tvárí, OCR, CLIP, Picasa import, prepracovanie tvárí) naraz.
// Čistá logika bez Android tried (HeavyGateTest). Režim výkonu (IndexPerf) sa nemení: ľahké úlohy
// (poloha, QR, duplikáty) bežia ďalej paralelne, ťažké len idú za sebou v poradí príchodu (FIFO).
//
// - Úloha si miesto berie lenivo — až keď naozaj načíta model (HeavySlot.ensure); bez práce nečaká.
// - Čakanie sa dá kedykoľvek zastaviť: `active()` sa kontroluje každých pollMs; zastavená úloha
//   miesto nedostane a acquire vráti null.
// - Reentrantné v rámci vlákna (indexer volaný priamo z iného indexera na tom istom vlákne sa nezasekne).
// - Čakajúci dostávajú miesto v poradí príchodu (lístky), nie náhodne.
class HeavyGate(private val pollMs: Long = DEFAULT_POLL_MS) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // kto miesto drží (a koľkokrát reentrantne) a poradie čakajúcich (lístky, FIFO)
    private var owner: Thread? = null
    private var holds = 0
    private val queue = ArrayDeque<Any>()

    // vyhodí sa, keď úlohu zastavili počas čakania na miesto (nie je to chyba, len koniec práce)
    class Stopped : RuntimeException("stopped while waiting for heavy slot")

    // Pridelené miesto. close() je idempotentné.
    inner class Lease internal constructor() : AutoCloseable {
        private var open = true

        override fun close() {
            lock.withLock {
                if (!open) return
                open = false
                holds--
                if (holds <= 0) {
                    holds = 0
                    owner = null
                    changed.signalAll()
                }
            }
        }
    }

    val isBusy: Boolean
        get() = lock.withLock { owner != null }

    // onWait sa zavolá najviac raz, len keď treba čakať (priebeh v notifikácii „čaká…“)
    fun acquire(active: () -> Boolean, onWait: () -> Unit = {}): Lease? {
        val me = Thread.currentThread()
        val ticket = Any()
        lock.withLock {
            if (owner === me) {
                holds++
                return Lease()
            }
            if (!active()) return null
            if (owner == null && queue.isEmpty()) return take(me)
            queue.addLast(ticket)
        }
        onWait()
        return waitFor(me, ticket, active)
    }

    private fun waitFor(me: Thread, ticket: Any, active: () -> Boolean): Lease? = lock.withLock {
        var lease: Lease? = null
        try {
            while (lease == null && active()) {
                if (owner == null && queue.firstOrNull() === ticket) {
                    queue.removeFirst()
                    lease = take(me)
                } else {
                    changed.await(pollMs, TimeUnit.MILLISECONDS)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        lease ?: giveUp(ticket)
    }

    // volať pod zámkom
    private fun take(me: Thread): Lease {
        owner = me
        holds = 1
        return Lease()
    }

    // volať pod zámkom: zastavená úloha uvoľní svoje miesto v poradí ďalšej
    private fun giveUp(ticket: Any): Lease? {
        queue.remove(ticket)
        changed.signalAll()
        return null
    }

    companion object {
        const val DEFAULT_POLL_MS = 300L

        // jedna brána pre celý proces
        val SHARED = HeavyGate()
    }
}

// Lenivé miesto v HeavyGate pre jeden beh úlohy: ensure() pri prvom modeli, close() vo finally.
// onResume sa zavolá po čakaní (úloha prepíše „čaká…“ svojím priebehom).
class HeavySlot(
    private val active: () -> Boolean,
    private val onWait: () -> Unit = {},
    private val onResume: () -> Unit = {},
    private val gate: HeavyGate = HeavyGate.SHARED,
) : AutoCloseable {
    private var lease: HeavyGate.Lease? = null

    val held: Boolean
        get() = lease != null

    // false = úlohu zastavili počas čakania
    fun ensure(): Boolean {
        if (lease != null) return true
        var waited = false
        lease = gate.acquire(active) {
            waited = true
            onWait()
        } ?: return false
        if (waited) onResume()
        return true
    }

    // ako ensure(), ale zastavenie vyhodí HeavyGate.Stopped (pre lenivé gettery modelov)
    fun require() {
        if (!ensure()) throw HeavyGate.Stopped()
    }

    override fun close() {
        lease?.close()
        lease = null
    }
}
