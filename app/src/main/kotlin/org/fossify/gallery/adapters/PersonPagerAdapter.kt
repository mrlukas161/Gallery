package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import com.bumptech.glide.Glide
import org.fossify.gallery.databinding.ItemPagerPhotoBinding
import java.io.File

// Stránky celoobrazovkového prehliadača fotiek OSOBY (swipe ostáva len v jej zozname).
// ViewPager v1 + GestureImageView = automatická kooperácia zoom/listovanie.
class PersonPagerAdapter(
    val activity: Activity,
    val paths: List<String>,
) : PagerAdapter() {

    override fun getCount() = paths.size

    override fun isViewFromObject(view: View, obj: Any) = view === obj

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val binding = ItemPagerPhotoBinding.inflate(LayoutInflater.from(container.context), container, false)
        Glide.with(activity)
            .load(File(paths[position]))
            .into(binding.pagerImage)
        container.addView(binding.root)
        return binding.root
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        container.removeView(obj as View)
    }
}
