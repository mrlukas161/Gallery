package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MemoriesAdapter
import org.fossify.gallery.databinding.ActivityDocsBinding
import org.fossify.gallery.helpers.Memories
import org.fossify.gallery.helpers.PathTransfer

// Spomienky: „Pred rokom", výlety a vydarené dni — poskladané z dátumov a polohy fotiek.
class MemoriesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDocsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.docsGrid.layoutManager = GridLayoutManager(this, 2)
        binding.docsFastscroller.updateColors(getProperPrimaryColor())
        binding.docsToolbar.title = getString(R.string.memories_title)
        binding.docsPlaceholder.text = getString(R.string.memories_loading)
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.docsAppbar, NavigationIcon.Arrow)
        // len placeholder — karty spomienok majú vlastný biely text na tmavom prekrytí,
        // plošné updateTextColors by ho vo svetlej téme prefarbilo na tmavý (nečitateľný)
        binding.docsPlaceholder.setTextColor(getProperTextColor())
    }

    private fun load() {
        ensureBackgroundThread {
            val items = try {
                Memories.build(this).filter { m -> m.paths.any { java.io.File(it).exists() } }
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.docsGrid.adapter = MemoriesAdapter(this, items) { m -> open(m) }
                binding.docsPlaceholder.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                if (items.isEmpty()) binding.docsPlaceholder.text = getString(R.string.memories_empty)
                binding.docsToolbar.title = getString(R.string.memories_title)
            }
        }
    }

    private fun open(m: Memories.Memory) {
        PathTransfer.forGrid = m.paths.take(2000)
        startActivity(Intent(this, PhotoGridActivity::class.java))
    }
}
