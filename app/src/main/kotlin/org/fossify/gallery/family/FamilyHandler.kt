package org.fossify.gallery.family

import java.util.concurrent.CopyOnWriteArrayList

// Rozhranie, cez ktoré F3 (op-log) a F4 (balíky k fotkám) napoja svoju logiku na prenos z F2.
//
// Prenos (FamilyServer + FamilyClient) sám vymieňa operácie z tabuľky `ops`: pošle vlastné opy
// (device_id = vlastné deviceId) s lamport > posledný potvrdený na druhej strane a prijaté opy uloží cez
// OpDao.insertOp s applied_at = null (duplicity podľa opId sa ignorujú). Aplikovanie je na F3 (OpApplier).
// Položky fronty `outbox` (stav pending) pošle cez /f/bundle; obsah balíka zostaví a spracuje F4.
//
// Všetky metódy sa volajú na pozadí (vlákno servera alebo synchronizácie), nikdy na hlavnom vlákne.
// Výnimka z handlera prenos nezhodí (FamilyHandlers ju zachytí), ale daná akcia sa ráta ako neúspešná.
interface FamilyHandler {
    // od peera prišli nové opy (už sú uložené v `ops` s applied_at = null); count = počet nových
    fun onOpsReceived(peer: PeerEntity, count: Int) {
        // predvolene nič
    }

    // Klient: zostaví dáta položky fronty pre /f/bundle. null = tento handler položku nepozná.
    // Ak ju nepozná žiadny handler, položka sa označí ako STATE_FAILED.
    fun buildBundle(peer: PeerEntity, item: OutboxEntity): ByteArray? = null

    // Server: prijatý balík. true = spracovaný, klient položku označí ako STATE_SENT.
    fun onBundle(peer: PeerEntity, kind: String, refKey: String, data: ByteArray): Boolean = false

    // synchronizácia s peerom prebehla celá (po /f/ack), na oboch stranách
    fun onSyncFinished(peer: PeerEntity) {
        // predvolene nič
    }

    // prepojenie s telefónom zaniklo (zrušené tu alebo na druhej strane)
    fun onUnpaired(deviceId: String) {
        // predvolene nič
    }
}

// Zoznam handlerov. F3/F4 sa zaregistrujú raz (napr. v App.onCreate): FamilyHandlers.register(MojHandler(ctx)).
object FamilyHandlers {
    private val handlers = CopyOnWriteArrayList<FamilyHandler>()

    fun register(handler: FamilyHandler) {
        if (handler !in handlers) handlers.add(handler)
    }

    fun unregister(handler: FamilyHandler) {
        handlers.remove(handler)
    }

    internal fun opsReceived(peer: PeerEntity, count: Int) = each { it.onOpsReceived(peer, count) }

    internal fun buildBundle(peer: PeerEntity, item: OutboxEntity): ByteArray? =
        handlers.firstNotNullOfOrNull { guarded { it.buildBundle(peer, item) } }

    internal fun bundle(peer: PeerEntity, kind: String, refKey: String, data: ByteArray): Boolean =
        handlers.any { guarded { it.onBundle(peer, kind, refKey, data) } == true }

    internal fun syncFinished(peer: PeerEntity) = each { it.onSyncFinished(peer) }

    internal fun unpaired(deviceId: String) = each { it.onUnpaired(deviceId) }

    private fun each(action: (FamilyHandler) -> Unit) {
        handlers.forEach { guarded { action(it) } }
    }

    // chyba v handleri (F3/F4) nesmie zhodiť server ani synchronizáciu
    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        android.util.Log.w("FamilyHandlers", "handler zlyhal", e)
        null
    }
}
