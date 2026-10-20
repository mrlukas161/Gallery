package org.fossify.gallery.family

import android.content.Context

// Napojenie op-logu (F3) na prenos (F2): pred odoslaním doplní snapshot/odtlačky, po prijatí
// opov ich aplikuje (OpApplier — potichu / návrh / počká). Registruje sa raz v App.onCreate.
class OpLogHandler(context: Context) : FamilyHandler {
    private val app = context.applicationContext

    override fun beforeOwnOps() {
        FamilySync(app).prepareOwnOps()
    }

    override fun onOpsReceived(peer: PeerEntity, count: Int) {
        FamilySync(app).retryPending()
    }
}
