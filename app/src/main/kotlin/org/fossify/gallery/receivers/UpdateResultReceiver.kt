package org.fossify.gallery.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

// Výsledok tichej samo-aktualizácie (PackageInstaller session z AppUpdater).
// PENDING_USER_ACTION príde len pri PRVEJ inštalácii cez appku (systém si vyžiada potvrdenie) —
// po nej je Galéria+ vlastným „installerom" a ďalšie aktualizácie prebehnú bez dialógov.
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(it)
                    } catch (ignored: Throwable) {
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // pri self-update systém proces reštartuje sám; toast sa ukáže, ak proces ešte žije
                try {
                    Toast.makeText(context, "Galéria+ aktualizovaná ✓", Toast.LENGTH_LONG).show()
                } catch (ignored: Throwable) {
                }
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "neznáma chyba"
                try {
                    Toast.makeText(context, "Aktualizácia zlyhala: $msg", Toast.LENGTH_LONG).show()
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
