package org.fossify.gallery.family

import android.app.Activity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R

// Akcia „Poslať do prepojeného telefónu“ z prehliadača (ViewPagerActivity) a z výberového režimu mriežky
// (MediaAdapter). Výber telefónu a spôsobu cez RadioGroupDialog (štýl appky), práca na pozadí.
fun Activity.sendToFamily(paths: List<String>) {
    if (paths.isEmpty()) return
    val app = applicationContext
    ensureBackgroundThread {
        val peers = try {
            FamilyStore(app).peers()
        } catch (ignored: RuntimeException) {
            emptyList()
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (peers.isEmpty()) {
                toast(R.string.family_send_no_peer)
                return@runOnUiThread
            }
            val items = ArrayList<RadioItem>()
            peers.forEachIndexed { i, peer ->
                items.add(RadioItem(i * 2, getString(R.string.family_send_with_file, peer.name)))
                items.add(RadioItem(i * 2 + 1, getString(R.string.family_send_results_only, peer.name)))
            }
            RadioGroupDialog(this, items, titleId = R.string.family_send_title) {
                val which = it as Int
                startFamilySend(paths, peers[which / 2], which % 2 == 0)
            }
        }
    }
}

private fun Activity.startFamilySend(paths: List<String>, peer: PeerEntity, withFile: Boolean) {
    val app = applicationContext
    ensureBackgroundThread {
        val queued = FamilySend.enqueue(app, paths, peer, withFile)
        if (queued == 0) {
            app.toast(R.string.family_send_nothing)
            return@ensureBackgroundThread
        }
        app.toast(app.resources.getQuantityString(R.plurals.family_send_queued, queued, queued, peer.name))
        val result = FamilySend.syncNow(app, peer.deviceId)
        if (result?.status != FamilyClient.Status.OK) {
            // fronta ostáva vo family.db, odíde pri najbližšom stretnutí telefónov
            app.toast(app.getString(R.string.family_send_later, peer.name))
        }
    }
}
