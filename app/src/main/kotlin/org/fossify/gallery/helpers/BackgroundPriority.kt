package org.fossify.gallery.helpers

import android.os.Process

// Indexery bežia s nízkou prioritou vlákna (THREAD_PRIORITY_BACKGROUND), aby neubrali výkon UI.
// Indexer sa môže spustiť aj priamo na vlákne volajúceho (ensureBackgroundThread mimo hlavného
// vlákna nespúšťa nové), preto sa pôvodná priorita po behu vráti.
@Suppress("TooGenericExceptionCaught") // priorita je len optimalizácia, nesmie zhodiť beh
object BackgroundPriority {
    // zníži prioritu aktuálneho vlákna; vráti pôvodnú pre restore() (null = nepodarilo sa zistiť)
    fun lower(): Int? {
        val previous = try {
            Process.getThreadPriority(Process.myTid())
        } catch (ignored: Exception) {
            null
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (ignored: Exception) {
        }
        return previous
    }

    fun restore(previous: Int?) {
        if (previous == null) return
        try {
            Process.setThreadPriority(previous)
        } catch (ignored: Exception) {
        }
    }

    inline fun <T> run(block: () -> T): T {
        val previous = lower()
        try {
            return block()
        } finally {
            restore(previous)
        }
    }
}
