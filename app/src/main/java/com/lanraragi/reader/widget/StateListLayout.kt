/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.list.ListScreenState

/**
 * A list screen's body: shows one of its slots for a [ListScreenState]
 * ([render]) and reports a retry tap ([onRetry]). It holds no screen state of
 * its own and never reads the slots' visibility back.
 *
 * The screen's layout supplies the slots as direct children, by id, so each screen
 * keeps its own loading look and content view:
 * - [R.id.state_progress]: shown while loading with nothing to show (a
 *   skeleton, a spinner);
 * - [R.id.state_message]: `@layout/view_error_state` (icon, title, message,
 *   retry), shown for an empty answer ([emptyText]) and for a failure with
 *   nothing retained (a failure that kept items shows them; the screen
 *   reports it through its own one-shot event);
 * - [R.id.state_content]: the list (or any content view), shown whenever
 *   there are items, also when a reload failed and kept them.
 * Any other child (a toolbar, a footer, a FAB) is left alone.
 */
class StateListLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Title of the message slot for an empty answer. */
    var emptyText: CharSequence = ""

    /** Called when the user taps retry under an error; without it the retry is hidden. */
    var onRetry: (() -> Unit)? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.StateListLayout)
        emptyText = a.getText(R.styleable.StateListLayout_stateEmptyText) ?: ""
        a.recycle()
    }

    private val progress: View? get() = slot(R.id.state_progress)
    private val message: View? get() = slot(R.id.state_message)
    private val content: View? get() = slot(R.id.state_content)

    /** A direct child only: a nested StateListLayout keeps its own slots. */
    private fun slot(id: Int): View? = (0 until childCount).map(::getChildAt).firstOrNull { it.id == id }

    /** Shows the slots for [state]. */
    fun render(state: ListScreenState<*>) {
        val showContent = state.items.isNotEmpty()
        progress?.visibility = if (state is ListScreenState.Loading) VISIBLE else GONE
        content?.visibility = if (showContent) VISIBLE else GONE
        val messageSlot = message ?: return
        when {
            showContent || state is ListScreenState.Loading -> messageSlot.visibility = GONE
            state is ListScreenState.Error -> showMessage(
                messageSlot,
                title = context.getText(R.string.lrr_error_title),
                detail = state.message,
                failed = true,
            )
            else -> showMessage(messageSlot, title = emptyText, detail = null, failed = false)
        }
    }

    private fun showMessage(slot: View, title: CharSequence, detail: CharSequence?, failed: Boolean) {
        slot.visibility = VISIBLE
        slot.findViewById<View>(R.id.error_icon)?.visibility = if (failed) VISIBLE else GONE
        slot.findViewById<TextView>(R.id.error_title)?.text = title
        slot.findViewById<TextView>(R.id.error_message)?.apply {
            text = detail
            visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE
        }
        val retry = onRetry
        slot.findViewById<View>(R.id.error_retry)?.apply {
            visibility = if (failed && retry != null) VISIBLE else GONE
            setOnClickListener { onRetry?.invoke() }
        }
    }
}
