package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.databinding.ActivityExploreBinding

// Hub „Preskúmať" (ako Google Photos) — jedno miesto namiesto rozhádzaných položiek v menu.
// Karty: Ľudia, Miesta (mapa), Podobné a duplikáty, Hľadať čokoľvek (hlavná lupa).
class ExploreActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityExploreBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.explorePeople.setOnClickListener {
            startActivity(Intent(this, PeopleActivity::class.java))
        }
        binding.explorePlaces.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        binding.exploreDocs.setOnClickListener {
            startActivity(Intent(this, DocsActivity::class.java))
        }
        binding.exploreSimilar.setOnClickListener {
            startActivity(Intent(this, CompareListActivity::class.java))
        }
        binding.exploreSearch.setOnClickListener {
            // otvor hlavné hľadanie galérie
            startActivity(Intent(this, SearchActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.exploreAppbar, NavigationIcon.Arrow)
    }
}
