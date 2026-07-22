package com.hippo.ehviewer.ui.scene.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.RoundSideRectDrawable
import com.hippo.ehviewer.R
import com.hippo.ehviewer.stats.ReadingStatsCalculator
import com.hippo.ehviewer.stats.TagPreferenceCalculator
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.util.collectFlow
import com.hippo.widget.AutoWrapLayout

/**
 * Reading-statistics page (issue #18): snapshot number tiles, per-server
 * breakdown, and the recently-completed list. Data via [ReadingStatsViewModel]
 * only; rows for the two dynamic sections are simple programmatic TextViews
 * (text-only presentation — no chart widgets by design).
 */
class ReadingStatsScene : BaseScene() {

    private var mToolbar: MaterialToolbar? = null
    private var mProgress: FrameLayout? = null
    private var mEmpty: TextView? = null
    private var mScroll: ScrollView? = null
    private var mTotalValue: TextView? = null
    private var mCompletedValue: TextView? = null
    private var mPagesValue: TextView? = null
    private var mPerServerContainer: LinearLayout? = null
    private var mRecentContainer: LinearLayout? = null
    private var mSectionRecent: TextView? = null
    private var mTagPreferenceContainer: LinearLayout? = null

    private lateinit var viewModel: ReadingStatsViewModel

    override fun getNavCheckedItem(): Int = R.id.nav_stats

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_reading_stats, container, false)

        viewModel = ViewModelProvider(requireActivity())[ReadingStatsViewModel::class.java]

        mToolbar = view.findViewById(R.id.toolbar)
        mProgress = view.findViewById(R.id.progress)
        mEmpty = view.findViewById(R.id.empty_view)
        mScroll = view.findViewById(R.id.content_scroll)
        mTotalValue = view.findViewById(R.id.stat_total_value)
        mCompletedValue = view.findViewById(R.id.stat_completed_value)
        mPagesValue = view.findViewById(R.id.stat_pages_value)
        mPerServerContainer = view.findViewById(R.id.per_server_container)
        mRecentContainer = view.findViewById(R.id.recent_container)
        mSectionRecent = view.findViewById(R.id.section_recent)
        mTagPreferenceContainer = view.findViewById(R.id.tag_preference_container)

        mToolbar?.apply {
            setTitle(R.string.nav_stats)
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
        }

        collectFlow(viewLifecycleOwner, viewModel.stats) { stats ->
            if (stats != null) bind(stats)
        }
        collectFlow(viewLifecycleOwner, viewModel.tagPreference) { preference ->
            if (preference != null) bindTagPreference(preference)
        }
        collectFlow(viewLifecycleOwner, viewModel.isLoading) { loading ->
            mProgress?.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) applyContentVisibility()
        }

        viewModel.load()
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mToolbar = null
        mProgress = null
        mEmpty = null
        mScroll = null
        mTotalValue = null
        mCompletedValue = null
        mPagesValue = null
        mPerServerContainer = null
        mRecentContainer = null
        mSectionRecent = null
        mTagPreferenceContainer = null
    }

    private fun applyContentVisibility() {
        val stats = viewModel.stats.value
        val empty = stats == null || stats.totalArchives == 0
        mEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        mScroll?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun bind(stats: ReadingStatsCalculator.ReadingStats) {
        mTotalValue?.text = stats.totalArchives.toString()
        mCompletedValue?.text = stats.completedCount.toString()
        mPagesValue?.text = stats.totalPagesRead.toString()

        mPerServerContainer?.let { container ->
            container.removeAllViews()
            for (server in stats.perServer) {
                container.addView(twoLineRow(
                    primary = server.serverName
                        ?: getString(R.string.stats_deleted_server, server.profileId),
                    secondary = getString(
                        R.string.stats_server_line,
                        server.archives, server.completed, server.pagesRead
                    ),
                ))
            }
        }

        mRecentContainer?.let { container ->
            container.removeAllViews()
            val visible = stats.recentlyCompleted.isNotEmpty()
            mSectionRecent?.visibility = if (visible) View.VISIBLE else View.GONE
            container.visibility = if (visible) View.VISIBLE else View.GONE
            for (entry in stats.recentlyCompleted) {
                container.addView(singleLineRow(entry.title))
            }
        }

        applyContentVisibility()
    }

    private fun bindTagPreference(preference: TagPreferenceCalculator.TagPreference) {
        val container = mTagPreferenceContainer ?: return
        container.removeAllViews()
        if (preference.isEmpty) return

        container.addView(sectionHeader(getString(R.string.stats_tag_preference)))
        addTagGroup(container, R.string.stats_tag_artists, preference.artists)
        addTagGroup(container, R.string.stats_tag_series, preference.series)
        addTagGroup(container, R.string.stats_tag_misc, preference.misc)
    }

    private fun addTagGroup(
        container: LinearLayout,
        titleRes: Int,
        entries: List<TagPreferenceCalculator.TagCount>,
    ) {
        if (entries.isEmpty()) return
        val context = requireContext()
        val inflater = LayoutInflater.from(context)
        val chipColor = AttrResources.getAttrColor(context, R.attr.tagBackgroundColor)

        container.addView(TextView(context).apply {
            text = getString(titleRes)
            textSize = 12f
            setTextColor(themeTextColor(android.R.attr.textColorSecondary))
            val pad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, 0)
        })

        val flow = AutoWrapLayout(context)
        container.addView(flow)
        for (entry in entries) {
            val chip = inflater.inflate(R.layout.item_gallery_tag, flow, false) as TextView
            chip.text = getString(R.string.stats_tag_chip, entry.tag, entry.count)
            chip.background = RoundSideRectDrawable(chipColor)
            flow.addView(chip)
        }
    }

    private fun sectionHeader(text: String): TextView {
        val context = requireContext()
        return TextView(context).apply {
            this.text = text
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeTextColor(android.R.attr.textColorPrimary))
            val pad = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, 0)
        }
    }

    private fun twoLineRow(primary: String, secondary: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            addView(TextView(context).apply {
                text = primary
                setTextColor(themeTextColor(android.R.attr.textColorPrimary))
            })
            addView(TextView(context).apply {
                text = secondary
                textSize = 12f
                setTextColor(themeTextColor(android.R.attr.textColorSecondary))
            })
        }
    }

    private fun singleLineRow(text: String): View {
        val context = requireContext()
        return TextView(context).apply {
            this.text = text
            maxLines = 1
            val pad = (6 * context.resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            setTextColor(themeTextColor(android.R.attr.textColorSecondary))
        }
    }

    private fun themeTextColor(attr: Int): Int =
        AttrResources.getAttrColor(requireContext(), attr)
}
