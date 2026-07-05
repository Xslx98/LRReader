package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R

/**
 * Shared dialog scaffolding for the tankoubon scenes ([TankoubonsScene] /
 * [TankoubonDetailScene]). Pure UI: builds the dialog, validates trivial
 * input (blank name), and hands the result to the caller — no ViewModel
 * knowledge.
 */
internal object TankDialogs {

    private const val DIALOG_PADDING_DP = 24

    /**
     * Single-line name input dialog (create / rename). Blank input is
     * rejected with a [R.string.tank_name_empty] toast; [onOk] only ever
     * sees a non-blank trimmed name.
     */
    fun showNameInputDialog(
        context: Context,
        @StringRes titleRes: Int,
        initial: String,
        onOk: (String) -> Unit,
    ) {
        val nameInput = EditText(context).apply {
            setHint(R.string.tank_name_hint)
            setText(initial)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(wrapDialogContent(context, nameInput))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, R.string.tank_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onOk(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Two-field summary + tags editor; [onOk] gets both values raw. */
    fun showMetaDialog(
        context: Context,
        initialSummary: String,
        initialTags: String,
        onOk: (summary: String, tags: String) -> Unit,
    ) {
        val summaryInput = EditText(context).apply {
            setHint(R.string.tank_summary_hint)
            setText(initialSummary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val tagsInput = EditText(context).apply {
            setHint(R.string.tank_tags_hint)
            setText(initialTags)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(summaryInput)
            addView(tagsInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.tank_edit_meta)
            .setView(wrapDialogContent(context, column))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onOk(summaryInput.text.toString(), tagsInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tank deletion confirm ([R.string.tank_delete_confirm]). */
    fun showDeleteConfirm(context: Context, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.tank_delete)
            .setMessage(R.string.tank_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Keyline-padded container for programmatically built dialog content. */
    private fun wrapDialogContent(ctx: Context, content: View): View {
        val pad = (DIALOG_PADDING_DP * ctx.resources.displayMetrics.density).toInt()
        return FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(content)
        }
    }
}
