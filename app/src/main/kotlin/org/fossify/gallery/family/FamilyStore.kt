package org.fossify.gallery.family

import android.content.Context
import android.os.Build
import android.provider.Settings

// Spárované telefóny a ich kľúče: jediné miesto, kde sa spolu menia riadok v `peers` (family.db)
// a zabalený kľúč (FamilyKeys). Volať len na pozadí (Room, Keystore).
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
        return true
    }

    // Zrušenie prepojenia na tomto telefóne. Riadok zmizne hneď, kľúč sa podrží, kým sa to pri
    // najbližšom stretnutí nedozvie aj druhý telefón (FamilyClient / FamilyServer → finishUnpair).
    fun unpair(deviceId: String) {
        db.peerDao().deletePeer(deviceId)
        prefs.setPendingUnpair(deviceId, true)
        FamilyHandlers.unpaired(deviceId)
    }

    // druhý telefón sa dozvedel o zrušení → zmaž aj kľúč
    fun finishUnpair(deviceId: String) {
        keys.delete(FamilyKeys.aliasFor(deviceId))
        prefs.setPendingUnpair(deviceId, false)
        prefs.rememberAddress(deviceId, null)
    }

    // prepojenie zrušil druhý telefón → zmaž všetko hneď
    fun forget(deviceId: String) {
        val known = peer(deviceId) != null
        db.peerDao().deletePeer(deviceId)
        finishUnpair(deviceId)
        if (known) FamilyHandlers.unpaired(deviceId)
    }
}
