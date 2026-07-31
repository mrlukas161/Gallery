package org.fossify.gallery.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

// Stránky hlavnej obrazovky sú pohľady deklarované priamo v activity_main.xml — adaptér ich
// len sprístupní stránkovaču. Vďaka tomu ostávajú platné všetky view-binding referencie
// MainActivity (binding.directoriesGrid a spol.) a kód priečinkov sa nemusí prepisovať.
class MainPagesAdapter(private val pages: List<View>) : PagerAdapter() {

    override fun getCount() = pages.size

    override fun isViewFromObject(view: View, obj: Any) = view === obj

    override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position]

    // pohľady sú stálou súčasťou XML, preto sa neodstraňujú
    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {}

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_FOLDERS = 1
        const val PAGE_RECENT = 2
        const val PAGE_EXPLORE = 3
    }
}
