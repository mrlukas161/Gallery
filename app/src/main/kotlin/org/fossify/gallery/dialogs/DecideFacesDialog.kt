package org.fossify.gallery.dialogs

import android.database.SQLException
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceVisibility
import org.fossify.gallery.faces.PeopleIndex
import org.fossify.gallery.faces.PersonGrouper
import org.fossify.gallery.helpers.FaceDecisions

// C11 — „Pomôž mi rozhodnúť“: otázky „Je to Ján, alebo Peter?“ na tváre s najmenším rozostupom medzi
// dvoma osobami (PersonGrouper.decisionQueue). Výrez tváre a voľby Ján / Peter / niekto iný / preskočiť.
// Odpoveď zapíše priradenie aj cannot-link na druhú osobu (FaceDecisions → FamilyRecorder), potom sa
// fronta prepočíta (PeopleIndex na pozadí) a príde ďalšia otázka. [pair] = len dvojica z upozornenia
// „Ján ↔ Peter sa pletú“ (C10). [onChanged] sa zavolá na hlavnom vlákne na konci, ak padla aspoň jedna odpoveď.
// Dialóg, nie obrazovka: nová aktivita by menila manifest.
class DecideFacesDialog(
    private val activity: BaseSimpleActivity,
    private val pair: Pair<Long, Long>? = null,
    private val onChanged: () -> Unit = {},
) {
    private val skipped = HashSet<Long>()
    private val visibility = FaceVisibility(activity)
    private var answered = 0

    init {
        next()
    }

    private class Question(val item: PersonGrouper.Scored, val first: String, val second: String)

    private fun next() {
        ensureBackgroundThread {
            val question = try {
                findQuestion()
            } catch (ignored: SQLException) {
                null
            } catch (ignored: IllegalStateException) {
                null
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (question == null) finish() else show(question)
            }
        }
    }

    private fun findQuestion(): Question? {
        val snapshot = PeopleIndex.get(activity)
        val names = snapshot.persons.mapNotNull { p -> p.id?.let { it to p.name } }.toMap()
        return PersonGrouper.decisionQueue(snapshot.contested, pair, skipped).asSequence()
            .filter { visibility.isVisible(it.face.mediaFullPath) }
            .mapNotNull { s ->
                val a = names[s.personId]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val b = names[s.secondId]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Question(s, a, b)
            }
            .firstOrNull()
    }

    private fun finish() {
        if (answered > 0) {
            activity.toast(R.string.tc2_decide_thanks)
            onChanged()
        } else {
            activity.toast(R.string.tc2_decide_nothing)
        }
    }

    private fun show(q: Question) {
        val margin = activity.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, margin / 2, 0, margin / 2)
        }
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, FACE_DP, activity.resources.displayMetrics)
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        holder.addView(image, LinearLayout.LayoutParams(size.toInt(), size.toInt()).apply { bottomMargin = margin / 2 })
        FaceCropLoader.load(q.item.face, image)
        holder.addView(
            MyTextView(activity).apply {
                text = activity.getString(R.string.tc2_decide_question, q.first, q.second)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, QUESTION_SP)
                gravity = Gravity.CENTER
                setPadding(margin, 0, margin, margin / 2)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        var dialog: AlertDialog? = null
        val faceId = q.item.face.id ?: return
        val both = listOf(q.item.personId, q.item.secondId)
        fun choice(label: String, action: () -> Unit) = holder.addView(
            row(label, margin) {
                dialog?.dismiss()
                action()
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        choice(q.first) { answer(faceId, q.item.personId, both) }
        choice(q.second) { answer(faceId, q.item.secondId, both) }
        choice(activity.getString(R.string.tc2_decide_someone_else)) { answer(faceId, null, both) }
        choice(activity.getString(R.string.tc2_decide_skip)) {
            skipped.add(faceId)
            next()
        }
        val builder = activity.getAlertDialogBuilder()
            .setNegativeButton(R.string.tc2_decide_done) { _, _ -> finish() }
            .setOnCancelListener { finish() }
        activity.setupDialogStuff(ScrollView(activity).apply { addView(holder) }, builder, R.string.tc2_decide_title) {
            dialog = it
        }
    }

    private fun answer(faceId: Long, chosen: Long?, both: List<Long>) {
        ensureBackgroundThread {
            val ok = try {
                FaceDecisions.answer(activity, faceId, chosen, both)
                true
            } catch (ignored: SQLException) {
                false
            } catch (ignored: IllegalStateException) {
                false
            }
            if (ok) answered++ else skipped.add(faceId)
            next()
        }
    }

    private fun row(text: String, margin: Int, onClick: () -> Unit): MyTextView {
        val ripple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        return MyTextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_SP)
            setPadding(margin, margin * ROW_PAD_NUM / ROW_PAD_DEN, margin, margin * ROW_PAD_NUM / ROW_PAD_DEN)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
    }

    companion object {
        private const val FACE_DP = 160f
        private const val QUESTION_SP = 18f
        private const val ROW_SP = 16f

        // zvislý okraj riadku voľby = 3/4 okraja aktivity (ako zoznamy volieb v Ľuďoch)
        private const val ROW_PAD_NUM = 3
        private const val ROW_PAD_DEN = 4
    }
}
