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
 * Regression test for the tag-edit dialog's delete affordance.
 *
 * Each editable tag chip used to render a "✕" glyph appended to the tag text, but
 * the glyph was cosmetic: the whole chip's onClick opened the *edit* dialog and
 * deleting a tag was only reachable via an undiscoverable long-press. Users tapped
 * the ✕ expecting a delete and nothing was removed.
 *
 * The fix gives every chip a dedicated ✕ delete button — its own view with a 48dp
 * touch target. Tapping ✕ deletes; tapping the tag text still edits. This locks
 * that split into [TagEditDialog.buildTagChip].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TagEditDialogDeleteChipTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density = context.resources.displayMetrics.density
    private val colorTag = Color.DKGRAY

    @Test
    fun tappingDeleteButton_deletesTag_withoutEditing() {
        var edited = false
        var deleted = false

        val chip = TagEditDialog.buildTagChip(
            context, density, colorTag, "picasso",
            onEdit = { edited = true },
            onDelete = { deleted = true },
        )

        val deleteButton = findDeleteButton(chip, "picasso")
        assertNotNull("chip must expose a dedicated ✕ delete button", deleteButton)

        deleteButton!!.performClick()

        assertTrue("tapping ✕ must delete the tag", deleted)
        assertFalse("tapping ✕ must not open the edit dialog", edited)
    }

    @Test
    fun tappingTagText_editsTag_withoutDeleting() {
        var edited = false
        var deleted = false

        val chip = TagEditDialog.buildTagChip(
            context, density, colorTag, "picasso",
            onEdit = { edited = true },
            onDelete = { deleted = true },
        )

        val label = findLabel(chip, "picasso")
        assertNotNull("chip must show the tag text as its own view", label)

        label!!.performClick()

        assertTrue("tapping the tag text must open the edit dialog", edited)
        assertFalse("tapping the tag text must not delete the tag", deleted)
    }

    @Test
    fun deleteButton_hasAccessibleTouchTarget() {
        val chip = TagEditDialog.buildTagChip(
            context, density, colorTag, "picasso",
            onEdit = {}, onDelete = {},
        )
        val deleteButton = findDeleteButton(chip, "picasso")!!

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

    /** The ✕ button carries a contentDescription naming the tag; the label does not. */
    private fun findDeleteButton(root: View, tag: String): View? {
        val expected = context.getString(R.string.lrr_delete_tag, tag)
        return firstOrNull(root) { it.contentDescription?.toString() == expected }
    }

    private fun findLabel(root: View, tag: String): View? =
        firstOrNull(root) { it is TextView && it.text?.toString() == tag }

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
