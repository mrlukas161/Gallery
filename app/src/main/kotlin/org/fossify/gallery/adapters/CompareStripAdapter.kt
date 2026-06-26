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

    fun toggleMark(pos: Int) {
        if (marked.contains(pos)) marked.remove(pos) else marked.add(pos)
        notifyItemChanged(pos)
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
            binding.root.setOnClickListener { onTap(pos) }
            binding.root.setOnLongClickListener {
                onLong(pos)
                true
            }
        }
    }
}
