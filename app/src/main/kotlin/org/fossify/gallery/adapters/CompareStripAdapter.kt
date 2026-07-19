package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.databinding.ItemCompareStripBinding
import java.io.File

// Filmstrip fotiek skupiny: ★ = najostrejšia, červené prekrytie = označené na zmazanie.
class CompareStripAdapter(
    val activity: Activity,
    val paths: List<String>,
    val onTap: (Int) -> Unit,
    val onLong: (Int) -> Unit,
) : RecyclerView.Adapter<CompareStripAdapter.ViewHolder>() {

    val marked = HashSet<Int>()
    var bestIndex = -1
    private var activeLeft = -1
    private var activeRight = -1

    fun toggleMark(pos: Int) {
        if (marked.contains(pos)) marked.remove(pos) else marked.add(pos)
        notifyItemChanged(pos)
    }

    // „Ponechať len túto": označí na zmazanie VŠETKY ostatné fotky skupiny (napr. 60 fotiek -> 1 ostáva)
    fun markAllExcept(pos: Int) {
        marked.clear()
        paths.indices.forEach { if (it != pos) marked.add(it) }
        notifyDataSetChanged()
    }

    // ktorá fotka je práve v ľavom (Ľ, modrá) a pravom (P, oranžová) paneli
    fun setActive(left: Int, right: Int) {
        val changed = setOf(activeLeft, activeRight, left, right).filter { it in paths.indices }
        activeLeft = left
        activeRight = right
        changed.forEach { notifyItemChanged(it) }
    }

    fun clearMarks() {
        marked.clear()
        notifyDataSetChanged()
    }

    fun markedPaths(): List<String> = marked.filter { it in paths.indices }.map { paths[it] }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCompareStripBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = paths.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    inner class ViewHolder(val binding: ItemCompareStripBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pos: Int) {
            Glide.with(activity).load(File(paths[pos])).centerCrop().into(binding.stripImage)
            binding.stripBest.visibility = if (pos == bestIndex) View.VISIBLE else View.GONE
            binding.stripOverlay.visibility = if (marked.contains(pos)) View.VISIBLE else View.GONE
            when (pos) {
                activeLeft -> {
                    binding.stripBorder.setBackgroundResource(org.fossify.gallery.R.drawable.border_pane_left)
                    binding.stripBorder.visibility = View.VISIBLE
                    binding.stripBadge.text = "Ľ"
                    binding.stripBadge.setBackgroundColor(0xFF1976D2.toInt())
                    binding.stripBadge.visibility = View.VISIBLE
                }
                activeRight -> {
                    binding.stripBorder.setBackgroundResource(org.fossify.gallery.R.drawable.border_pane_right)
                    binding.stripBorder.visibility = View.VISIBLE
                    binding.stripBadge.text = "P"
                    binding.stripBadge.setBackgroundColor(0xFFFB8C00.toInt())
                    binding.stripBadge.visibility = View.VISIBLE
                }
                else -> {
                    binding.stripBorder.visibility = View.GONE
                    binding.stripBadge.visibility = View.GONE
                }
            }
            binding.root.setOnClickListener { onTap(pos) }
            binding.root.setOnLongClickListener {
                onLong(pos)
                true
            }
        }
    }
}
