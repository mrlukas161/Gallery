package org.fossify.gallery.compare

import android.content.Context
import org.fossify.gallery.R
import org.fossify.gallery.helpers.CompareScoring
import org.fossify.gallery.helpers.PhotoScore
import org.fossify.gallery.helpers.Reason

// Texty porovnávača zo string resources: titulok, štítky panelov, zdôvodnenia skóre, názvy priečinkov.
// Používa ho aj CompareAnalysis.describe (zoznam skupín, porovnávač na PC).
object CompareTexts {
    private const val TENTH = 10.0

    // „Séria 3 / 17 · 12 záberov · 3. 6. 2024“ (poradie skupiny len pri otvorení zo zoznamu skupín)
    fun title(context: Context, duplicates: Boolean, count: Int, firstTaken: Long?, group: Int, groups: Int): String {
        val kind = if (group > 0 && groups > 0) {
            val res = if (duplicates) R.string.pa_group_duplicates else R.string.pa_group_series
            context.getString(res, group, groups)
        } else {
            context.getString(if (duplicates) R.string.pa_duplicates else R.string.pa_series)
        }
        val parts = arrayListOf(kind, context.resources.getQuantityString(R.plurals.pa_shots, count, count))
        if (firstTaken != null && firstTaken > 0L) parts.add(CompareFormat.date(firstTaken))
        return parts.joinToString(CompareFormat.SEPARATOR)
    }

    // „3 / 24 · 14:05:32 · 12 Mpx · 3,2 MB · Fotoaparát“
    fun infoLine(context: Context, position: Int, total: Int, info: PhotoInfo?): String {
        val parts = arrayListOf(context.getString(R.string.pa_position, position, total))
        if (info != null) {
            if (info.taken > 0L) parts.add(CompareFormat.time(info.taken))
            if (info.pixels > 0L) parts.add(CompareFormat.megapixels(info.width, info.height))
            if (info.bytes > 0L) parts.add(CompareFormat.size(info.bytes))
            parts.add(folderName(context, info.path))
        }
        return parts.joinToString(CompareFormat.SEPARATOR)
    }

    // „★ najlepšia · skóre 92 % · ostrejšia o 18 %, všetci 3 majú otvorené oči“
    fun scoreLine(context: Context, score: PhotoScore?, path: String): String {
        if (score == null) return ""
        val parts = ArrayList<String>()
        if (score.isBest) parts.add(context.getString(R.string.pa_best))
        parts.add(context.getString(R.string.pa_score, score.percent))
        val why = reasons(context, score, path)
        parts.add(why.ifEmpty { if (score.isBest) context.getString(R.string.pa_best_no_reason) else "" })
        return parts.filter { it.isNotEmpty() }.joinToString(CompareFormat.SEPARATOR)
    }

    fun reasons(context: Context, score: PhotoScore, path: String): String =
        score.reasons.joinToString(", ") { reason(context, it, path) }

    fun reason(context: Context, r: Reason, path: String): String {
        val res = context.resources
        val mpx = CompareFormat.decimal(r.value / TENTH)
        return when (r.kind) {
            Reason.Kind.SHARPER -> context.getString(R.string.pa_reason_sharper, r.value)
            Reason.Kind.BLURRIER -> context.getString(R.string.pa_reason_blurrier, r.value)
            Reason.Kind.ALL_EYES_OPEN -> res.getQuantityString(R.plurals.pa_reason_all_eyes_open, r.value, r.value)
            Reason.Kind.EYES_CLOSED -> res.getQuantityString(R.plurals.pa_reason_eyes_closed, r.value, r.value)
            Reason.Kind.MORE_FACES -> context.getString(R.string.pa_reason_more_faces, r.value, r.total)
            Reason.Kind.FEWER_FACES -> context.getString(R.string.pa_reason_fewer_faces, r.value, r.total)
            Reason.Kind.BETTER_EXPOSURE -> context.getString(R.string.pa_reason_better_exposure)
            Reason.Kind.WORSE_EXPOSURE -> context.getString(R.string.pa_reason_worse_exposure)
            Reason.Kind.SMILES -> res.getQuantityString(R.plurals.pa_reason_smiles, r.value, r.value)
            Reason.Kind.HIGHER_RESOLUTION -> context.getString(R.string.pa_reason_higher_res, mpx)
            Reason.Kind.LOWER_RESOLUTION -> context.getString(R.string.pa_reason_lower_res, mpx)
            Reason.Kind.ORIGINAL_FOLDER -> 
                context.getString(R.string.pa_reason_original_folder, folderName(context, path))
            Reason.Kind.OLDER_FILE -> context.getString(R.string.pa_reason_older)
        }
    }

    fun folderName(context: Context, path: String): String = when (CompareFormat.folderKind(path)) {
        FolderKind.CAMERA -> context.getString(R.string.pa_folder_camera)
        FolderKind.SCREENSHOTS -> context.getString(R.string.pa_folder_screenshots)
        FolderKind.WHATSAPP -> context.getString(R.string.pa_folder_whatsapp)
        FolderKind.DOWNLOAD -> context.getString(R.string.pa_folder_download)
        FolderKind.OTHER -> path.substringBeforeLast('/').substringAfterLast('/')
    }

    // „5 fotiek (23 MB)“ do potvrdenia
    fun photos(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.pa_photos_acc, count, count)

    // ★ a druhá najlepšia podľa skóre
    fun bestTwo(scores: Map<String, PhotoScore>): Pair<String?, String?> {
        val ordered = CompareScoring.ordered(scores)
        return ordered.getOrNull(0) to ordered.getOrNull(1)
    }
}
