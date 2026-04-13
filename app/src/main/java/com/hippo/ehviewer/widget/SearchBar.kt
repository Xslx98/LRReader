/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhTagDatabase.Companion.NAMESPACE_TO_PREFIX
import com.lanraragi.reader.client.api.LRRTagCache
import com.hippo.ehviewer.settings.AppearanceSettings
import kotlinx.coroutines.launch
import com.hippo.lib.yorozuya.AnimationUtils
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.lib.yorozuya.SimpleAnimatorListener
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.view.ViewTransition

class SearchBar : CardView,
    View.OnClickListener,
    TextView.OnEditorActionListener,
    TextWatcher,
    SearchEditText.SearchEditTextListener {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    companion object {
        private const val TAG = "SearchBar"

        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_STATE = "state"

        private const val ANIMATE_TIME = 300L

        const val STATE_NORMAL = 0
        const val STATE_SEARCH = 1
        const val STATE_SEARCH_LIST = 2
    }

    private var mState = STATE_NORMAL

    private val mRect = Rect()
    private var mWidth = 0
    private var mHeight = 0
    private var mBaseHeight = 0
    private var mProgress = 0f

    private var mMenuButton: ImageView
    private var mTitleTextView: TextView
    private var mActionButton: ImageView
    private var mEditText: SearchEditText
    private var mListView: ListView
    private var mListContainer: View
    private var mListHeader: View

    private var mViewTransition: ViewTransition

    private var mSearchDatabase: SearchDatabase
    private var mSuggestionList: MutableList<Suggestion>
    private var mSuggestionAdapter: SuggestionAdapter

    private var mHelper: Helper? = null
    private var mOnStateChangeListener: OnStateChangeListener? = null
    private var mSuggestionProvider: SuggestionProvider? = null

    private var mAllowEmptySearch = true

    private var mInAnimation = false

    private var showTranslation: Boolean

    private var isComeFromDownload = false

    init {
        showTranslation = AppearanceSettings.getShowTagTranslations()
        mSearchDatabase = SearchDatabase.getInstance(getContext())

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.widget_search_bar, this)
        mMenuButton = ViewUtils.`$$`(this, R.id.search_menu) as ImageView
        mTitleTextView = ViewUtils.`$$`(this, R.id.search_title) as TextView
        mActionButton = ViewUtils.`$$`(this, R.id.search_action) as ImageView
        mEditText = ViewUtils.`$$`(this, R.id.search_edit_text) as SearchEditText
        mListContainer = ViewUtils.`$$`(this, R.id.list_container)
        mListView = ViewUtils.`$$`(mListContainer, R.id.search_bar_list) as ListView
        mListHeader = ViewUtils.`$$`(mListContainer, R.id.list_header)

        mViewTransition = ViewTransition(mTitleTextView, mEditText)

        mTitleTextView.setOnClickListener(this)
        mMenuButton.setOnClickListener(this)
        mActionButton.setOnClickListener(this)
        mEditText.setSearchEditTextListener(this)
        mEditText.setOnEditorActionListener(this)
        mEditText.addTextChangedListener(this)

        // Get base height
        ViewUtils.measureView(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mBaseHeight = measuredHeight

        mSuggestionList = ArrayList()
        mSuggestionAdapter = SuggestionAdapter(LayoutInflater.from(getContext()))
        mListView.adapter = mSuggestionAdapter
        mListView.setOnItemClickListener { _, _, position, _ ->
            mSuggestionList[position].onClick()
        }
        mListView.setOnItemLongClickListener { _, _, position, _ ->
            mSuggestionList[position].onLongClick()
            true
        }
    }

    private fun addListHeader() {
        mListHeader.visibility = VISIBLE
    }

    private fun removeListHeader() {
        mListHeader.visibility = GONE
    }

    private fun updateSuggestions(scrollToTop: Boolean = true) {
        mSuggestionList.clear()
        val editable = mEditText.text
        var text = ""
        if (editable != null) {
            text = editable.toString()
        }

        if (mSuggestionProvider != null) {
            val suggestions = mSuggestionProvider!!.providerSuggestions(text)
            if (suggestions != null && suggestions.isNotEmpty()) {
                mSuggestionList.addAll(suggestions)
            }
        }

        val keywords = mSearchDatabase.getSuggestions(text, 128)
        for (keyword in keywords) {
            mSuggestionList.add(HistorySuggestion(keyword))
        }

        // Track keywords already added to avoid duplicates between local and server tags
        val existingTagKeys = mutableSetOf<String>()

        val ehTagDatabase = EhTagDatabase.getInstance(getContext())
        if (!TextUtils.isEmpty(text) && ehTagDatabase != null) {
            val s = text.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (s.isNotEmpty()) {
                var keyword = ""
                for (i in s.indices.reversed()) {
                    if (s[i].contains(":") || s[i].contains("$")) {
                        break
                    } else {
                        keyword = if (keyword.isEmpty()) {
                            s[i]
                        } else {
                            s[i] + " " + keyword
                        }
                    }
                }
                keyword = keyword.trim()

                if (keyword.isNotEmpty()) {
                    val searchHints = ehTagDatabase.suggest(keyword)

                    for (searchHint in searchHints) {
                        existingTagKeys.add(searchHint.second.lowercase())
                        if (showTranslation) {
                            mSuggestionList.add(TagSuggestion(searchHint.first, searchHint.second))
                        } else {
                            mSuggestionList.add(TagSuggestion(null, searchHint.second))
                        }
                    }
                }
            }
        }

        // LRR server tag suggestions (from in-memory cache, no network call)
        if (text.isNotEmpty()) {
            val lrrTags = LRRTagCache.suggest(text)
            for (tag in lrrTags) {
                val fullTag = "${tag.namespace}:${tag.text}"
                if (fullTag.lowercase() !in existingTagKeys) {
                    mSuggestionList.add(TagSuggestion(null, fullTag))
                }
            }
        }

        if (mSuggestionList.isEmpty()) {
            removeListHeader()
        } else {
            addListHeader()
        }
        // BaseAdapter only supports notifyDataSetChanged() — no granular notifications
        @Suppress("NotifyDataSetChanged")
        mSuggestionAdapter.notifyDataSetChanged()

        if (scrollToTop) {
            mListView.setSelection(0)
        }
    }

    fun setAllowEmptySearch(allowEmptySearch: Boolean) {
        mAllowEmptySearch = allowEmptySearch
    }

    val editTextTextSize: Float
        get() = mEditText.textSize

    fun setEditTextHint(hint: CharSequence?) {
        mEditText.hint = hint
    }

    fun setEditTextHint(resId: Int) {
        mEditText.setHint(resId)
    }

    fun setHelper(helper: Helper?) {
        mHelper = helper
    }

    fun setOnStateChangeListener(listener: OnStateChangeListener?) {
        mOnStateChangeListener = listener
    }

    fun setSuggestionProvider(suggestionProvider: SuggestionProvider?) {
        mSuggestionProvider = suggestionProvider
    }

    fun setText(text: String?) {
        mEditText.setText(text)
    }

    fun cursorToEnd() {
        val text = mEditText.text
        if (text != null) {
            mEditText.setSelection(mEditText.text!!.length)
        }
    }

    fun setTitle(title: String?) {
        mTitleTextView.text = title
    }

    fun setTitle(resId: Int) {
        mTitleTextView.setText(resId)
    }

    fun setSearch(search: String?) {
        mTitleTextView.text = search
        mEditText.setText(search)
    }

    fun setLeftDrawable(drawable: Drawable?) {
        if (drawable == null) {
            mMenuButton.visibility = GONE
        }
        mMenuButton.setImageDrawable(drawable)
    }

    fun setLeftDrawable(view: ImageView?) {
        if (view == null) {
            mMenuButton.visibility = GONE
        }
        if (view != null) {
            mMenuButton = view
        }
    }

    fun setRightDrawable(drawable: Drawable?) {
        mActionButton.setImageDrawable(drawable)
    }

    fun setLeftIconVisibility(visibility: Int) {
        mMenuButton.visibility = visibility
    }

    fun setRightIconVisibility(visibility: Int) {
        mActionButton.visibility = visibility
    }

    fun setEditTextMargin(left: Int, right: Int) {
        val lp = mEditText.layoutParams as MarginLayoutParams
        lp.leftMargin = left
        lp.rightMargin = right
        mEditText.layoutParams = lp
    }

    fun setIsComeFromDownload(isComeFromDownload: Boolean) {
        this.isComeFromDownload = isComeFromDownload
    }

    private fun applySearch() {
        if (mEditText.text == null) {
            return
        }
        val query = mEditText.text.toString().trim()
        query.replace("\n", "")
        if (!mAllowEmptySearch && TextUtils.isEmpty(query)) {
            return
        }

        // Put it into db
        mSearchDatabase.addQuery(query)
        // Callback
        mHelper?.onApplySearch(query)
    }

    fun applySearch(hideKeyboard: Boolean) {
        if (hideKeyboard) {
            hideKeyBoard()
        }
        applySearch()
    }

    fun hideKeyBoard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(this.windowToken, 0)
    }

    override fun onClick(v: View) {
        when (v) {
            mTitleTextView -> mHelper?.onClickTitle()
            mMenuButton -> mHelper?.onClickLeftIcon()
            mActionButton -> mHelper?.onClickRightIcon()
        }
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (v === mEditText) {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) {
                applySearch()
                return true
            }
        }
        return false
    }

    fun getState(): Int = mState

    fun setState(state: Int) {
        setState(state, true)
    }

    fun setState(state: Int, animation: Boolean) {
        if (mState != state) {
            val oldState = mState
            mState = state

            when (oldState) {
                STATE_NORMAL -> {
                    mViewTransition.showView(1, animation)
                    mEditText.requestFocus()
                    if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
                STATE_SEARCH -> {
                    if (state == STATE_NORMAL) {
                        mViewTransition.showView(0, animation)
                    } else if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
                STATE_SEARCH_LIST -> {
                    hideImeAndSuggestionsList(animation)
                    if (state == STATE_NORMAL) {
                        mViewTransition.showView(0, animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
                else -> {
                    mViewTransition.showView(1, animation)
                    mEditText.requestFocus()
                    if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
            }
        }
    }

    @JvmOverloads
    fun showImeAndSuggestionsList(animation: Boolean = true) {
        // Show ime
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(mEditText, 0)
        // Pre-populate LRR tag cache if needed (async, won't block UI)
        if (LRRTagCache.needsRefresh()) {
            try {
                ServiceRegistry.coroutineModule.ioScope.launch {
                    LRRTagCache.getTags()
                    // Refresh suggestions on the UI thread once cache is ready
                    post { updateSuggestions(false) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-populate LRR tag cache", e)
            }
        }
        // update suggestion for show suggestions list
        updateSuggestions()
        // Show suggestions list
        if (animation) {
            val oa = ObjectAnimator.ofFloat(this, "progress", 1f)
            oa.duration = ANIMATE_TIME
            oa.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
            oa.addListener(object : SimpleAnimatorListener() {
                override fun onAnimationStart(animation: Animator) {
                    mListContainer.visibility = VISIBLE
                    mInAnimation = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    mInAnimation = false
                }
            })
            oa.setAutoCancel(true)
            oa.start()
        } else {
            mListContainer.visibility = VISIBLE
            setProgress(1f)
        }
    }

    private fun hideImeAndSuggestionsList(animation: Boolean = true) {
        // Hide ime
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(this.windowToken, 0)
        // Hide suggestions list
        if (animation) {
            val oa = ObjectAnimator.ofFloat(this, "progress", 0f)
            oa.duration = ANIMATE_TIME
            oa.interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
            oa.addListener(object : SimpleAnimatorListener() {
                override fun onAnimationStart(animation: Animator) {
                    mInAnimation = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    mListContainer.visibility = GONE
                    mInAnimation = false
                }
            })
            oa.setAutoCancel(true)
            oa.start()
        } else {
            setProgress(0f)
            mListContainer.visibility = GONE
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (mListContainer.visibility == VISIBLE) {
            mWidth = right - left
            mHeight = bottom - top
        }
    }

    @Suppress("unused")
    fun setProgress(progress: Float) {
        mProgress = progress
        invalidate()
    }

    @Suppress("unused")
    fun getProgress(): Float = mProgress

    override fun draw(canvas: Canvas) {
        if (mInAnimation) {
            val state = canvas.save()
            val bottom = MathUtils.lerp(mBaseHeight, mHeight, mProgress)
            mRect.set(0, 0, mWidth, bottom)
            canvas.clipRect(mRect)
            super.draw(canvas)
            canvas.restoreToCount(state)
        } else {
            super.draw(canvas)
        }
    }

    // TextWatcher
    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        // Empty
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // Empty
    }

    override fun afterTextChanged(s: Editable) {
        updateSuggestions()
    }

    // SearchEditText.SearchEditTextListener
    override fun onClick() {
        mHelper?.onSearchEditTextClick()
    }

    override fun onBackPressed() {
        mHelper?.onSearchEditTextBackPressed()
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putInt(STATE_KEY_STATE, mState)
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable(STATE_KEY_SUPER))
            setState(state.getInt(STATE_KEY_STATE), false)
        }
    }

    interface Helper {
        fun onClickTitle()
        fun onClickLeftIcon()
        fun onClickRightIcon()
        fun onSearchEditTextClick()
        fun onApplySearch(query: String)
        fun onSearchEditTextBackPressed()
    }

    interface OnStateChangeListener {
        fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean)
    }

    interface SuggestionProvider {
        fun providerSuggestions(text: String): List<Suggestion>?
    }

    abstract class Suggestion {
        abstract fun getText(textSize: Float): CharSequence?
        abstract fun getText(textView: TextView): CharSequence?
        abstract fun onClick()
        abstract fun onLongClick()
    }

    private inner class SuggestionAdapter(
        private val inflater: LayoutInflater
    ) : BaseAdapter() {

        override fun getCount(): Int = mSuggestionList.size

        override fun getItem(position: Int): Any = mSuggestionList[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val linearLayout: LinearLayout = if (convertView == null) {
                inflater.inflate(R.layout.search_suggestion_item, parent, false) as LinearLayout
            } else {
                convertView as LinearLayout
            }
            val hintView = linearLayout.findViewById<TextView>(R.id.hintView)
            val textView = linearLayout.findViewById<TextView>(R.id.textView)

            val suggestion = mSuggestionList[position]

            val hint = suggestion.getText(hintView) as? String
            val text = suggestion.getText(textView) as? String

            hintView.text = hint

            if (text.isNullOrEmpty()) {
                textView.visibility = GONE
            } else {
                textView.visibility = VISIBLE
                textView.text = text
            }

            return linearLayout
        }
    }

    private inner class TagSuggestion(
        val show: String?,
        val mKeyword: String
    ) : Suggestion() {

        override fun getText(textSize: Float): CharSequence? = null

        override fun getText(textView: TextView): CharSequence? {
            return if (textView.id == R.id.hintView) {
                mKeyword
            } else {
                show
            }
        }

        /**
         * 无法替换中文
         */
        fun removeCommonSubstring(text1: String, text2: String): String {
            val m = text1.length
            var match = ""

            for (i in m - 1 downTo 0) {
                val tmp = text1.substring(i, m)
                if (!text2.contains(tmp)) {
                    break
                } else {
                    match = tmp
                }
            }

            return text1.substring(0, m - match.length)
        }

        fun replaceCommonSubstring(tagKey: String, editable: Editable): String {
            var key = tagKey
            if (editable.toString().contains(" ")) {
                val builder = StringBuilder(editable)
                val c = ' '
                while (builder[builder.length - 1] != c) {
                    builder.deleteCharAt(builder.length - 1)
                }

                while (builder.isNotEmpty() && builder[builder.length - 1] == c) {
                    builder.deleteCharAt(builder.length - 1)
                }

                builder.append("  ").append(tagKey)
                key = builder.toString()
            }
            return key
        }

        override fun onClick() {
            val editable = mEditText.text
            if (editable != null) {
                val tagKey = rebuildKeyword(mKeyword)
                val newText = replaceCommonSubstring(tagKey, editable)
                mEditText.setText(newText)
                mEditText.setSelection(mEditText.text!!.length)
            }
        }

        private fun rebuildKeyword(key: String): String {
            val strings = key.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (strings.size != 2) {
                return key
            }
            val tagName = strings[1]
            if (isComeFromDownload) {
                val groupName = strings[0]
                return "$groupName:$tagName"
            }
            if (NAMESPACE_TO_PREFIX.containsKey(strings[0])) {
                val groupName = NAMESPACE_TO_PREFIX[strings[0]]
                return "$groupName\"$tagName\$\""
            } else {
                return key
            }
        }

        override fun onLongClick() {
            mSearchDatabase.deleteQuery(mKeyword)
            updateSuggestions(false)
        }
    }

    private inner class HistorySuggestion(
        private val mQuery: String
    ) : Suggestion() {

        override fun getText(textSize: Float): CharSequence? = null

        override fun getText(textView: TextView): CharSequence? {
            return if (textView.id == R.id.hintView) {
                mQuery
            } else {
                null
            }
        }

        override fun onClick() {
            mEditText.setText(mQuery)
            mEditText.setSelection(mEditText.text!!.length)
        }

        override fun onLongClick() {
            mSearchDatabase.deleteQuery(mQuery)
            updateSuggestions(false)
        }
    }
}
