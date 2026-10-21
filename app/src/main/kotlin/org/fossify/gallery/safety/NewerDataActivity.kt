package org.fossify.gallery.safety

import android.os.Bundle
import android.widget.FrameLayout
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity

// A4: obrazovka namiesto pádu, keď sú dáta z novšej verzie appky (DbVersionGuard). Nič neotvára ani
// nemení — po zatvorení dialógu sa appka zavrie. Dáta ostanú nedotknuté pre novšiu verziu.
class NewerDataActivity : SimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { setBackgroundColor(getProperBackgroundColor()) })
        val databases = intent.getStringExtra(EXTRA_DATABASES).orEmpty()
        DataSafetyUi.messageDialog(this, getString(R.string.ta1_newer_message, databases)) {
            setCancelable(false)
            setPositiveButton(R.string.ta1_close) { _, _ -> finishAffinity() }
        }
    }

    companion object {
        const val EXTRA_DATABASES = "databases"
    }
}
