package com.lanraragi.reader.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.lanraragi.reader.R
import java.util.Locale

/**
 * Pager bar: the item total, previous / next, a sliding row of page numbers
 * and a page-size picker.
 *
 * The caller owns the state and pushes it with [bind], which never calls the
 * listener back; [listener] hears only what the user did.
 */
class PaginationIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnChangedListener {
        /** The user chose [page] (1-based). */
        fun onPageSelected(page: Int)

        /** The user chose [pageSize]; the indicator is back on page 1, and so must the listener be. */
        fun onPageSizeSelected(pageSize: Int)
    }

    var listener: OnChangedListener? = null

    private val totalView: TextView
    private val previousButton: TextView
    private val nextButton: TextView
    private val pageTips: List<TextView>
    private val pageSizeSpinner: Spinner

    private var total = 0
    private var pageSize = 1
    private var page = 1
    private var pageSizeChoices = IntArray(0)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.widget_pagination_indicator, this, true)
        totalView = findViewById(R.id.pagination_total)
        previousButton = findViewById(R.id.pagination_previous)
        nextButton = findViewById(R.id.pagination_next)
        pageSizeSpinner = findViewById(R.id.pagination_page_size)
        // bind() is the only source of the page size, also after a state restore.
        pageSizeSpinner.isSaveEnabled = false

        val tipRow = findViewById<LinearLayout>(R.id.pagination_tips)
        pageTips = List(MAX_PAGE_TIPS) {
            val tip = inflater.inflate(R.layout.pagination_tile, tipRow, false) as TextView
            tip.setOnClickListener { selectPage(tip.tag as Int) }
            tipRow.addView(tip)
            tip
        }
        previousButton.setText(R.string.pagination_previous_symbol)
        previousButton.contentDescription = context.getString(R.string.pagination_previous_page)
        previousButton.setOnClickListener { selectPage(page - 1) }
        nextButton.setText(R.string.pagination_next_symbol)
        nextButton.contentDescription = context.getString(R.string.pagination_next_page)
        nextButton.setOnClickListener { selectPage(page + 1) }
    }

    /** Shows the given state. [page] is clamped for display; the listener is not called. */
    fun bind(total: Int, pageSize: Int, page: Int, pageSizeChoices: IntArray) {
        this.total = total
        this.pageSize = pageSize
        this.page = page
        bindPageSizeChoices(pageSizeChoices)
        val selection = pageSizeChoices.indexOf(pageSize)
        if (selection >= 0 && selection != pageSizeSpinner.selectedItemPosition) {
            pageSizeSpinner.setSelection(selection, false)
        }
        render()
    }

    private fun bindPageSizeChoices(choices: IntArray) {
        if (choices.contentEquals(pageSizeChoices)) return
        pageSizeChoices = choices
        val suffix = context.getString(R.string.pagination_page_and_item)
        val adapter = ArrayAdapter(context, R.layout.pagination_page_size_item, choices.map { "$it$suffix" })
        adapter.setDropDownViewResource(R.layout.pagination_page_size_dropdown_item)
        pageSizeSpinner.adapter = adapter
        pageSizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectPageSize(pageSizeChoices[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun selectPage(target: Int) {
        val pageCount = PageWindow.pageCount(total, pageSize)
        if (target == page || target !in 1..pageCount) return
        page = target
        render()
        listener?.onPageSelected(target)
    }

    /** A spinner also reports its selection after layout, so an unchanged size is not a user choice. */
    private fun selectPageSize(size: Int) {
        if (size == pageSize) return
        pageSize = size
        page = 1
        render()
        listener?.onPageSizeSelected(size)
    }

    private fun render() {
        val pageCount = PageWindow.pageCount(total, pageSize)
        page = PageWindow.clampPage(page, pageCount)
        val totalText = context.getString(R.string.pagination_count) + total
        totalView.text = totalText
        bindArrow(previousButton, page > 1)
        bindArrow(nextButton, page < pageCount)

        val visible = PageWindow.visiblePages(page, pageCount, MAX_PAGE_TIPS).toList()
        pageTips.forEachIndexed { index, tip ->
            val number = visible.getOrNull(index)
            tip.visibility = if (number == null) View.GONE else View.VISIBLE
            if (number != null) {
                tip.tag = number
                tip.text = String.format(Locale.getDefault(), "%d", number)
                tip.isSelected = number == page
            }
        }
    }

    /** The tile drawable and text color key on "selected", which for an arrow means "can move". */
    private fun bindArrow(arrow: TextView, canMove: Boolean) {
        arrow.isEnabled = canMove
        arrow.isSelected = canMove
    }

    private companion object {
        const val MAX_PAGE_TIPS = 5
    }
}
