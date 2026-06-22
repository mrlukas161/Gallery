package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.databinding.ItemPersonPhotoBinding

class PersonPhotosAdapter(
    val activity: Activity,
    val paths: List<String>,
    val onClick: (String) -> Unit,
) : RecyclerView.Adapter<PersonPhotosAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = paths.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(paths[position])
    }

    inner class ViewHolder(val binding: ItemPersonPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(path: String) {
            Glide.with(activity).load(path).centerCrop().into(binding.photoImage)
            binding.root.setOnClickListener { onClick(path) }
        }
    }
}
