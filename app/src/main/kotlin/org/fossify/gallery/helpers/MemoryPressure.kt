package org.fossify.gallery.helpers

import java.util.concurrent.CopyOnWriteArraySet

// Kritický nedostatok pamäte (App.onTrimMemory RUNNING_CRITICAL / COMPLETE) → poslucháči (IndexingService
// zastaví automaticky spustené ťažké úlohy). Bez Android tried; poslucháč sa volá z vlákna na pozadí.
object MemoryPressure {
    private val critical = CopyOnWriteArraySet<() -> Unit>()

    fun addCriticalListener(listener: () -> Unit) {
        critical.add(listener)
    }

    fun removeCriticalListener(listener: () -> Unit) {
        critical.remove(listener)
    }

    @Suppress("TooGenericExceptionCaught") // jeden poslucháč nesmie zablokovať ostatných
    fun notifyCritical() {
        for (listener in critical) {
            try {
                listener()
            } catch (ignored: RuntimeException) {
            }
        }
    }
}
