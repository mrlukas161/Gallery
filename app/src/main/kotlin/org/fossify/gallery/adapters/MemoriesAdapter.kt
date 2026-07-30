package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.databinding.ItemMemoryBinding
import org.fossify.gallery.helpers.Memories
import java.io.File

// Karty spomienok: obálka + názov udalosti + dátum a počet fotiek.
class MemoriesAdapter(
    val activity: Activity,
    val items: List<Memories.Memory>,
    val onClick: (Memories.Memory) -> Unit,
) : RecyclerView.Adapter<MemoriesAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMemoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(val binding: ItemMemoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(m: Memories.Memory) {
            Glide.with(activity).load(File(m.paths.first())).centerCrop().into(binding.memoryImage)
            binding.memoryTitle.text = m.title
            binding.memorySubtitle.text = m.subtitle
            binding.root.setOnClickListener { onClick(m) }
        }
    }
}
