package com.hippo.ehviewer.ui.scene.gallery.detail

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the tag-edit dialog's *namespace* delete affordance.
 *
 * Namespace labels (the bold chips like "language", "parody") used to be deletable
 * only via an undiscoverable long-press, even after tag chips gained a visible ✕
 * delete button. That was inconsistent: tapping a namespace did nothing obvious.
 *
 * The fix gives every namespace chip the same dedicated ✕ delete button — its own
 * view with a 48dp touch target and a TalkBack contentDescription. Tapping ✕ removes
 * the whole namespace group (and all its tags); tapping the label edits the
 * namespace. This locks that split into [TagEditDialog.buildNamespaceChip], mirroring
 * [TagEditDialogDeleteChipTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagEditDialogDeleteNamespaceChipTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density = context.resources.displayMetrics.density
    private val colorNamespace = Color.MAGENTA

    @Test
    fun tappingDeleteButton_deletesNamespace_withoutEditing() {
        var edited = false
        var deleted = false

        val chip = TagEditDialog.buildNamespaceChip(
            context, density, colorNamespace, "language",
            onEdit = { edited = true },
            onDelete = { deleted = true },
        )

        val deleteButton = findDeleteButton(chip, "language")
        assertNotNull("namespace chip must expose a dedicated ✕ delete button", deleteButton)

        deleteButton!!.performClick()

        assertTrue("tapping ✕ must delete the namespace group", deleted)
        assertFalse("tapping ✕ must not open the edit dialog", edited)
    }

    @Test
    fun tappingNamespaceLabel_editsNamespace_withoutDeleting() {
        var edited = false
        var deleted = false

        val chip = TagEditDialog.buildNamespaceChip(
            context, density, colorNamespace, "language",
            onEdit = { edited = true },
            onDelete = { deleted = true },
        )

        val label = findLabel(chip, "language")
        assertNotNull("namespace chip must show the namespace text as its own view", label)

        label!!.performClick()

        assertTrue("tapping the namespace label must open the edit dialog", edited)
        assertFalse("tapping the namespace label must not delete the namespace", deleted)
    }

    @Test
    fun deleteButton_hasAccessibleTouchTarget() {
        val chip = TagEditDialog.buildNamespaceChip(
            context, density, colorNamespace, "language",
            onEdit = {}, onDelete = {},
        )
        val deleteButton = findDeleteButton(chip, "language")!!

        val min = (48 * density).toInt()
        assertTrue(
            "✕ touch target must be at least 48dp tall",
            deleteButton.minimumHeight >= min,
        )
        assertTrue(
            "✕ touch target must be at least 48dp wide",
            deleteButton.minimumWidth >= min,
        )
    }

    // ---- view-tree helpers ----

    /** The ✕ button carries a contentDescription naming the namespace; the label does not. */
    private fun findDeleteButton(root: View, namespace: String): View? {
        val expected = context.getString(R.string.lrr_delete_namespace, namespace)
        return firstOrNull(root) { it.contentDescription?.toString() == expected }
    }

    private fun findLabel(root: View, namespace: String): View? =
        firstOrNull(root) { it is TextView && it.text?.toString() == namespace }

    private fun firstOrNull(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                firstOrNull(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }
}
