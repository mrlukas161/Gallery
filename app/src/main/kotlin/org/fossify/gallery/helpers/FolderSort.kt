package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.fossify.gallery.R

// Zoradenie priečinkov — spoločné pre stránku Priečinky aj pre skratku na Domove.
// Voľba sa drží v tých istých predvoľbách ako ostatné vlastné nastavenia Galérie+ („galeria_faces").
object FolderSort {
    private const val PREFS = "galeria_faces"
    private const val KEY = "folder_sort_mode"

    const val BY_NAME = 0
    const val BY_MODIFIED = 1

    fun mode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, BY_MODIFIED)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, mode)
            .apply()
    }

    fun labelRes(mode: Int): Int = if (mode == BY_NAME) {
        R.string.folder_sort_name
    } else {
        R.string.folder_sort_modified
    }

    // dialóg s dvoma možnosťami; po výbere sa hneď zavrie a nechá volajúceho prekresliť zoznam
    fun showDialog(activity: Activity, onChanged: () -> Unit) {
        val labels = arrayOf(
            activity.getString(R.string.folder_sort_name),
            activity.getString(R.string.folder_sort_modified),
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_sort_title)
            .setSingleChoiceItems(labels, mode(activity)) { dialog, which ->
                setMode(activity, which)
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }
}
