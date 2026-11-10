package com.lanraragi.reader.ui.reader

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stamp card is measured UNSPECIFIED and shown at its measured size. A
 * short stamp text made the card too narrow for its buttons, so DELETE was
 * clipped off (#89): both buttons must fit inside the card whatever the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StampCardLayoutTest {

    @Test
    fun aShortStampText_stillFitsBothButtons() {
        val card = layOut("first stamp")

        val edit = card.findViewById<View>(R.id.stamp_card_edit)
        val delete = card.findViewById<View>(R.id.stamp_card_delete)
        val right = card.width - card.paddingRight
        assertTrue("EDIT is ${edit.width}px wide", edit.width > 0)
        assertTrue("DELETE is ${delete.width}px wide", delete.width > 0)
        assertTrue(
            "DELETE ends at ${rightEdge(delete, card)}, the card's content at $right",
            rightEdge(delete, card) <= right,
        )
    }

    private fun layOut(text: String): View {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)
        val card = LayoutInflater.from(context).inflate(R.layout.stamp_view_card, FrameLayout(context), false)
        card.findViewById<TextView>(R.id.stamp_card_text).text = text
        // As GalleryStampOps sizes the popup.
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        return card
    }

    /** [view]'s right edge in [root]'s coordinates. */
    private fun rightEdge(view: View, root: View): Int {
        var x = view.right
        var parent = view.parent as View
        while (parent !== root) {
            x += parent.left
            parent = parent.parent as View
        }
        return x
    }
}
