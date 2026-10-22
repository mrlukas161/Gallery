package org.fossify.gallery.helpers

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

// Drží jeden drahý prostriedok (model, Tesseract) otvorený len kým sa používa: po idleMs bez použitia
// ho zavrie, ďalšie použitie ho znova lenivo otvorí. Čistá logika bez Android tried (IdleCloserTest).
//
// Bezpečné voči súbehu: počítadlo používania — zatvorenie (časovač aj release() pri nedostatku pamäte)
// nikdy nezavrie prostriedok, ktorý práve niekto používa; release() počas používania ho zavrie hneď,
// ako posledný používateľ skončí. open() beží pod zámkom (volať len z pozadia), block() mimo neho.
// Časovač beží na spoločnom vlákne, ktoré po chvíli bez práce samo skončí (nič nebeží trvalo).
class IdleCloser<T : Any>(
    private val idleMs: Long,
    private val open: () -> T?,
    private val close: (T) -> Unit,
    private val scheduler: Scheduler = defaultScheduler,
) {
    fun interface Cancel {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, task: Runnable): Cancel
    }

    private val lock = Any()
    private var value: T? = null
    private var users = 0
    private var pending: Cancel? = null
    private var releaseWhenIdle = false
    private var generation = 0L

    val isOpen: Boolean
        get() = synchronized(lock) { value != null }

    // null = prostriedok sa nepodarilo otvoriť (block sa nezavolá)
    fun <R> use(block: (T) -> R): R? {
        val v = acquire() ?: return null
        try {
            return block(v)
        } finally {
            done()
        }
    }

    // nad otvoreným prostriedkom (bez otvárania a bez predĺženia času), napr. zmazanie cache
    fun ifOpen(block: (T) -> Unit) {
        synchronized(lock) { value }?.let(block)
    }

    // zavrie hneď, alebo keď dobehne posledné používanie (onTrimMemory, vypnutie režimu)
    fun release() {
        synchronized(lock) {
            if (users == 0) closeNow() else releaseWhenIdle = true
        }
    }

    private fun acquire(): T? = synchronized(lock) {
        pending?.cancel()
        pending = null
        generation++
        val cur = value ?: open()?.also { value = it }
        if (cur != null) {
            users++
        } else if (users == 0) {
            releaseWhenIdle = false
        }
        cur
    }

    private fun done() {
        synchronized(lock) {
            users--
            if (users > 0) return
            if (releaseWhenIdle) {
                closeNow()
            } else {
                val gen = ++generation
                pending = scheduler.schedule(idleMs) { onIdle(gen) }
            }
        }
    }

    private fun onIdle(gen: Long) {
        synchronized(lock) {
            if (gen == generation && users == 0) closeNow()
        }
    }

    @Suppress("TooGenericExceptionCaught") // natívne zatváranie nesmie zhodiť appku
    private fun closeNow() {
        pending?.cancel()
        pending = null
        generation++
        releaseWhenIdle = false
        val v = value ?: return
        value = null
        try {
            close(v)
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val THREAD_KEEP_ALIVE_S = 5L

        // jedno démonické vlákno pre všetky časovače; bez naplánovanej práce do pár sekúnd skončí
        val defaultScheduler: Scheduler by lazy {
            val executor = ScheduledThreadPoolExecutor(1, ThreadFactory { r ->
                Thread(r, "idle-closer").apply { isDaemon = true }
            })
            executor.setKeepAliveTime(THREAD_KEEP_ALIVE_S, TimeUnit.SECONDS)
            executor.allowCoreThreadTimeOut(true)
            executor.removeOnCancelPolicy = true
            Scheduler { delayMs, task ->
                val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                Cancel { future.cancel(false) }
            }
        }
    }
}
