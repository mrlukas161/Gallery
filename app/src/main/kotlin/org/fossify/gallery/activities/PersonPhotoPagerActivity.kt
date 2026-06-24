package org.fossify.gallery.activities

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.viewpager.widget.ViewPager
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.PersonPagerAdapter
import org.fossify.gallery.databinding.ActivityPersonPagerBinding
import java.io.File

// Celoobrazovkový prehliadač fotiek osoby — listovanie len v jej fotkách, + názov / zdieľať / upraviť / vymazať.
class PersonPhotoPagerActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityPersonPagerBinding::inflate)
    private var paths = ArrayList<String>()
    private var pendingDeletePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        paths = intent.getStringArrayListExtra(PATHS) ?: arrayListOf()
        val start = intent.getIntExtra(START_INDEX, 0)
        binding.personPager.adapter = PersonPagerAdapter(this, paths)
        if (start in paths.indices) {
            binding.personPager.setCurrentItem(start, false)
        }
        binding.personPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) = updateTitle()
        })
        updateTitle()
        binding.pagerBack.setOnClickListener { finish() }
        binding.pagerShare.setOnClickListener { shareCurrent() }
        binding.pagerEdit.setOnClickListener { editCurrent() }
        binding.pagerDelete.setOnClickListener { confirmDelete() }
    }

    private fun currentPath(): String? = paths.getOrNull(binding.personPager.currentItem)

    private fun updateTitle() {
        binding.pagerTitle.text = currentPath()?.substringAfterLast('/').orEmpty()
    }

    private fun uriFor(path: String): Uri =
        FileProvider.getUriForFile(this, "$packageName.provider", File(path))

    private fun shareCurrent() {
        val path = currentPath() ?: return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uriFor(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Throwable) {
            toast(R.string.action_failed)
        }
    }

    private fun editCurrent() {
        val path = currentPath() ?: return
        try {
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uriFor(path), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.edit)))
        } catch (e: Throwable) {
            toast(R.string.action_failed)
        }
    }

    private fun confirmDelete() {
        val path = currentPath() ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> doDelete(path) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doDelete(path: String) {
        ensureBackgroundThread {
            val uri = contentUriForPath(path)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                when {
                    uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        try {
                            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                            pendingDeletePath = path
                            startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
                        } catch (e: Throwable) {
                            toast(R.string.action_failed)
                        }
                    }

                    uri != null -> {
                        val ok = try {
                            contentResolver.delete(uri, null, null) > 0
                        } catch (e: Throwable) {
                            false
                        }
                        if (ok) removePath(path) else toast(R.string.action_failed)
                    }

                    else -> {
                        val ok = try {
                            File(path).delete()
                        } catch (e: Throwable) {
                            false
                        }
                        if (ok) removePath(path) else toast(R.string.action_failed)
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) {
            pendingDeletePath?.let { removePath(it) }
        }
        pendingDeletePath = null
    }

    private fun removePath(path: String) {
        val idx = paths.indexOf(path)
        if (idx < 0) return
        paths.removeAt(idx)
        if (paths.isEmpty()) {
            finish()
            return
        }
        binding.personPager.adapter = PersonPagerAdapter(this, paths)
        binding.personPager.setCurrentItem(idx.coerceAtMost(paths.lastIndex), false)
        updateTitle()
    }

    private fun contentUriForPath(path: String): Uri? {
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    companion object {
        const val PATHS = "paths"
        const val START_INDEX = "start_index"
        private const val REQ_DELETE = 7012
    }
}
