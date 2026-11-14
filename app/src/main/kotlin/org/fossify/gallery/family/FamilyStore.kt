package org.fossify.gallery.family

import android.content.Context
import android.os.Build
import android.provider.Settings

// Spárované telefóny a ich kľúče: jediné miesto, kde sa spolu menia riadok v `peers` (family.db)
// a zabalený kľúč (FamilyKeys). Volať len na pozadí (Room, Keystore).
@Suppress("TooManyFunctions")
class FamilyStore(context: Context) {
    private val appContext = context.applicationContext
    private val db = FamilyDatabase.getInstance(appContext)
    private val keys = FamilyKeys(appContext.getSharedPreferences(FamilyKeys.PREFS, Context.MODE_PRIVATE))
    val prefs = FamilyPrefs(appContext)

    fun selfId(): String = db.metaDao().deviceId()

    // názov tohto telefónu, ktorý uvidí druhá strana (predvolene systémový názov zariadenia alebo model)
    fun selfName(): String = db.metaDao().get(MetaEntity.KEY_DEVICE_NAME)
        ?: PairUri.cleanName(
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL.orEmpty(),
        )

    fun setSelfName(name: String) = db.metaDao().put(MetaEntity(MetaEntity.KEY_DEVICE_NAME, PairUri.cleanName(name)))

    fun peers(): List<PeerEntity> = db.peerDao().getPeers()

    fun peer(deviceId: String): PeerEntity? = db.peerDao().getPeer(deviceId)

    // kľúč páru pre spárovaný telefón aj pre telefón čakajúci na oznámenie o zrušení
    fun pairKey(deviceId: String): ByteArray? {
        if (peer(deviceId) == null && !prefs.isPendingUnpair(deviceId)) return null
        return keys.load(peer(deviceId)?.keyAlias ?: FamilyKeys.aliasFor(deviceId))
    }

    // Spárovaný telefón bez kľúča páru (záloha Androidu kľúče vynecháva, Keystore sa neobnovuje): riadok
    // v family.db prežil obnovu, kľúč nie. Taký telefón treba znova spárovať; synchronizácia ho preskočí.
    fun needsRepairing(deviceId: String): Boolean = peer(deviceId) != null && pairKey(deviceId) == null

    // uloží výsledok párovania; false = kľúč sa nepodarilo uložiť (prepojenie nevznikne)
    fun savePairing(result: PairingProtocol.Result): Boolean {
        val alias = FamilyKeys.aliasFor(result.peerId)
        if (!keys.store(alias, result.pairKey)) return false
        prefs.setPendingUnpair(result.peerId, false)
        val previous = peer(result.peerId)
        db.peerDao().upsertPeer(
            PeerEntity(
                deviceId = result.peerId,
                name = result.peerName,
                publicTag = FamilyCrypto.publicTag(result.peerId),
                keyAlias = alias,
                pairedAt = System.currentTimeMillis(),
                // opätovné spárovanie toho istého telefónu nezačína synchronizáciu od nuly
                lastLamportSeen = previous?.lastLamportSeen ?: 0,
            ),
        )
        if (previous == null) afterNewPairing(result.peerId)
        refreshSchedule(appContext)
        return true
    }

    // Nový telefón: operácie odložené pri zrušení starého prepojenia s ním sa vrátia a Ľudia sa zverejnia znova
    // (snapshot na pozadí s priebehom, nie v obsluhe požiadavky) — inak by nový telefón nedostal zmeny z času,
    // keď nebol spárovaný žiadny telefón a recorder nič nezapisoval.
    @Suppress("TooGenericExceptionCaught")
    private fun afterNewPairing(peerId: String) {
        try {
            db.opDao().unparkFromDevice(peerId)
            FamilySync(appContext).resetSnapshot()
            FamilySnapshot.start(appContext)
        } catch (e: RuntimeException) {
            android.util.Log.w("FamilyStore", "príprava po spárovaní zlyhala", e)
        }
    }

    // Zrušenie prepojenia na tomto telefóne. Riadok zmizne hneď, kľúč sa podrží, kým sa to pri
    // najbližšom stretnutí nedozvie aj druhý telefón (FamilyClient / FamilyServer → finishUnpair).
    fun unpair(deviceId: String) {
        db.peerDao().deletePeer(deviceId)
        prefs.setPendingUnpair(deviceId, true)
        cleanupAfterUnpair(deviceId)
        FamilyHandlers.unpaired(deviceId)
    }

    // Upratanie po zrušení prepojenia (plán F2.2): fronta pre ten telefón sa zahodí (fotky na disku ostanú),
    // jeho neaplikované operácie sa odložia nabok (nemažú sa, op-log je nenahraditeľný), otvorené návrhy od neho
    // sa uzavrú. Pri zrušení posledného prepojenia sa snapshot pri ďalšom spárovaní zopakuje.
    @Suppress("TooGenericExceptionCaught")
    private fun cleanupAfterUnpair(deviceId: String) {
        try {
            FamilySend.dropQueue(db, deviceId)
            familyExclusive { db.opDao().parkFromDevice(deviceId, System.currentTimeMillis()) }
            db.proposalDao().closeFromDevice(deviceId, ProposalEntity.STATE_DISMISSED)
            db.metaDao().delete(lastSyncKey(deviceId))
            if (peers().isEmpty()) FamilySync(appContext).resetSnapshot()
        } catch (e: RuntimeException) {
            android.util.Log.w("FamilyStore", "upratanie po zrušení prepojenia zlyhalo", e)
        }
    }

    // posledná celá synchronizácia s telefónom (po /f/ack na oboch stranách); 0 = ešte nikdy
    fun markSynced(deviceId: String) =
        db.metaDao().put(MetaEntity(lastSyncKey(deviceId), System.currentTimeMillis().toString()))

    fun lastSync(deviceId: String): Long = db.metaDao().get(lastSyncKey(deviceId))?.toLongOrNull() ?: 0

    private fun lastSyncKey(deviceId: String) = "f068_last_sync:$deviceId"

    // druhý telefón sa dozvedel o zrušení → zmaž aj kľúč
    fun finishUnpair(deviceId: String) {
        keys.delete(FamilyKeys.aliasFor(deviceId))
        prefs.setPendingUnpair(deviceId, false)
        prefs.rememberAddress(deviceId, null)
        // posledné prepojenie zaniklo → okno synchronizácie sa zruší (ak nečaká spracovanie)
        refreshSchedule(appContext)
    }

    // prepojenie zrušil druhý telefón → zmaž všetko hneď
    fun forget(deviceId: String) {
        val known = peer(deviceId) != null
        db.peerDao().deletePeer(deviceId)
        finishUnpair(deviceId)
        if (known) {
            cleanupAfterUnpair(deviceId)
            FamilyHandlers.unpaired(deviceId)
        }
    }
}

// F5: okno synchronizácie pri nabíjaní má zmysel len so spárovaným telefónom (alebo pri odloženom spracovaní)
@Suppress("TooGenericExceptionCaught")
private fun refreshSchedule(context: Context) {
    try {
        org.fossify.gallery.jobs.FamilySyncJob.refresh(context)
    } catch (e: RuntimeException) {
        android.util.Log.w("FamilyStore", "plánovanie synchronizácie zlyhalo", e)
    }
}
