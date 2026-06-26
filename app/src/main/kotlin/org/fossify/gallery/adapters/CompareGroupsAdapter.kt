package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.databinding.ItemPersonBinding
import java.io.File

// Mriežka skupín podobných/burst fotiek (titulka = prvá fotka, počet v rohu).
class CompareGroupsAdapter(
    val activity: Activity,
    val groups: List<List<String>>,
    val onClick: (List<String>) -> Unit,
) : RecyclerView.Adapter<CompareGroupsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    inner class ViewHolder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: List<String>) {
            Glide.with(activity).load(File(group.first())).centerCrop().into(binding.personImage)
            binding.personCount.text = group.size.toString()
            binding.root.setOnClickListener { onClick(group) }
        }
    }
}
