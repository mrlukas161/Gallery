package org.fossify.gallery.activities

import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.gallery.adapters.PersonPagerAdapter
import org.fossify.gallery.databinding.ActivityPersonPagerBinding

// Celoobrazovkový prehliadač fotiek jednej osoby — listovanie ostáva LEN v rámci jej fotiek.
class PersonPhotoPagerActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonPagerBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val paths = intent.getStringArrayListExtra(PATHS) ?: arrayListOf()
        val start = intent.getIntExtra(START_INDEX, 0)
        binding.personPager.adapter = PersonPagerAdapter(this, paths)
        if (start in paths.indices) {
            binding.personPager.setCurrentItem(start, false)
        }
        binding.pagerBack.setOnClickListener { finish() }
    }

    companion object {
        const val PATHS = "paths"
        const val START_INDEX = "start_index"
    }
}
