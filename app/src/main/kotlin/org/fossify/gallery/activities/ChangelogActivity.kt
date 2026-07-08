package org.fossify.gallery.activities

import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.databinding.ActivityChangelogBinding

// História zmien priamo v aplikácii (assets/changelog.html) — Lukáš si podľa nej odkontroluje funkcie.
class ChangelogActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityChangelogBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.changelogWeb.apply {
            settings.javaScriptEnabled = false
            settings.textZoom = 100
            loadUrl("file:///android_asset/changelog.html")
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.changelogAppbar, NavigationIcon.Arrow)
    }
}
