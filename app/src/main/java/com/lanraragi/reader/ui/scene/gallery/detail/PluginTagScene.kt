package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.tagtranslation.TagTranslationDatabase
import com.lanraragi.reader.ui.collectFlow
import com.lanraragi.reader.ui.scene.ToolbarScene

/**
 * Choose a server metadata plugin for one archive, wait for its run, and
 * review what it proposes. Only the review's apply button writes; going back
 * or leaving discards the run. Logic lives in [PluginTagViewModel].
 */
class PluginTagScene : ToolbarScene() {

    private lateinit var viewModel: PluginTagViewModel

    private var list: RecyclerView? = null
    private var status: View? = null
    private var progress: View? = null
    private var message: TextView? = null
    private var retry: Button? = null
    private var applyBar: View? = null
    private var applyError: TextView? = null
    private var apply: Button? = null
    private var adapter: PluginTagAdapter? = null
    private var argDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Scene-scoped: the flow belongs to this archive's screen alone. Here and not
        // with the views, because the stage may ask hasInSceneBack before they exist.
        viewModel = ViewModelProvider(this)[PluginTagViewModel::class.java]
    }

    override fun onCreateView3(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_plugin_tags, container, false)
        val archive = arguments?.let { BundleCompat.getParcelable(it, KEY_ARCHIVE, Archive::class.java) }
        if (archive == null) {
            view.post { finish() }
            return view
        }
        viewModel.init(archive)

        list = view.findViewById(R.id.plugin_tag_list)
        status = view.findViewById(R.id.plugin_tag_status)
        progress = view.findViewById(R.id.plugin_tag_progress)
        message = view.findViewById(R.id.plugin_tag_message)
        retry = view.findViewById(R.id.plugin_tag_retry)
        applyBar = view.findViewById(R.id.plugin_tag_apply_bar)
        applyError = view.findViewById(R.id.plugin_tag_apply_error)
        apply = view.findViewById(R.id.plugin_tag_apply)

        val translations = if (AppearanceSettings.getShowTagTranslations()) {
            TagTranslationDatabase.getInstance(view.context)
        } else {
            null
        }
        adapter = PluginTagAdapter(
            translations,
            onPlugin = ::onPluginClicked,
            onTag = viewModel::setTagChecked,
            onTitle = viewModel::setTitleChecked,
            onSummary = viewModel::setSummaryChecked,
        )
        list?.layoutManager = LinearLayoutManager(view.context)
        list?.adapter = adapter
        // Toggling a row must not flash it.
        list?.itemAnimator = null
        retry?.setOnClickListener { viewModel.retry() }
        apply?.setOnClickListener { viewModel.apply() }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(R.string.plugin_tags_fetch)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
        if (adapter == null) return
        collectFlow(viewLifecycleOwner, viewModel.state) { render(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        argDialog?.dismiss()
        argDialog = null
        list = null
        status = null
        progress = null
        message = null
        retry = null
        applyBar = null
        applyError = null
        apply = null
        adapter = null
    }

    override fun onNavigationClick(view: View) {
        onBackPressed()
    }

    override fun hasInSceneBack(): Boolean = viewModel.backStaysInScene()

    override fun onBackPressed() {
        if (viewModel.backStaysInScene()) viewModel.back() else super.onBackPressed()
    }

    private fun render(state: PluginTagUiState) {
        val context = context ?: return
        invalidateBack()
        when (state) {
            PluginTagUiState.Loading -> showStatus(null, busy = true)
            is PluginTagUiState.Plugins -> {
                if (state.plugins.isEmpty()) {
                    showStatus(getString(R.string.plugin_tags_none))
                } else {
                    showRows(state.plugins.map { PluginTagRow.Plugin(it) })
                }
            }
            is PluginTagUiState.Running -> showStatus(
                getString(R.string.plugin_tags_running, state.plugin.name) + "\n\n" +
                    getString(R.string.plugin_tags_running_hint),
                busy = true,
            )
            is PluginTagUiState.Review -> renderReview(context, state)
            is PluginTagUiState.Failed ->
                showStatus(failureText(context, state.failure), canRetry = state.canRetry)
            is PluginTagUiState.Applied -> {
                // Not written: the server already had those tags since the plugin ran.
                val text = if (state.wrote) R.string.plugin_tags_applied else R.string.plugin_tags_no_new
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                arguments?.getString(KEY_RESULT)?.let { parentFragmentManager.setFragmentResult(it, Bundle.EMPTY) }
                finish()
            }
        }
    }

    private fun renderReview(context: Context, state: PluginTagUiState.Review) {
        if (!state.hasProposals) {
            showStatus(getString(R.string.plugin_tags_no_new))
            return
        }
        showRows(pluginTagReviewRows(state))
        applyBar?.visibility = View.VISIBLE
        val count = state.changeCount
        apply?.text = getString(R.string.plugin_tags_apply, count)
        apply?.isEnabled = count > 0 && !state.applying
        applyError?.text = state.applyFailure?.let { failureText(context, it) }
        applyError?.visibility = if (state.applyFailure != null) View.VISIBLE else View.GONE
    }

    private fun showRows(rows: List<PluginTagRow>) {
        status?.visibility = View.GONE
        applyBar?.visibility = View.GONE
        list?.visibility = View.VISIBLE
        adapter?.submitList(rows)
    }

    private fun showStatus(text: String?, busy: Boolean = false, canRetry: Boolean = false) {
        list?.visibility = View.GONE
        applyBar?.visibility = View.GONE
        status?.visibility = View.VISIBLE
        progress?.visibility = if (busy) View.VISIBLE else View.GONE
        message?.text = text
        message?.visibility = if (text != null) View.VISIBLE else View.GONE
        retry?.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun failureText(context: Context, failure: PluginTagFailure): String = when (failure) {
        PluginTagFailure.KeyRequired -> getString(R.string.plugin_tags_key_required)
        PluginTagFailure.Unsupported -> getString(R.string.plugin_tags_unsupported)
        PluginTagFailure.TimedOut -> getString(R.string.lrr_timeout_error)
        PluginTagFailure.Busy -> getString(R.string.plugin_tags_busy)
        is PluginTagFailure.PluginError -> listOfNotNull(
            getString(R.string.plugin_tags_failed), failure.message?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")
        is PluginTagFailure.Other -> friendlyError(context, failure.cause)
    }

    private fun onPluginClicked(plugin: MetadataPlugin) {
        val label = plugin.oneshotLabel
        if (label == null) {
            viewModel.run(plugin, null)
            return
        }
        val context = context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val frame = FrameLayout(context).apply {
            val margin = resources.getDimensionPixelSize(R.dimen.keyline_margin)
            setPadding(margin, 0, margin, 0)
            addView(input)
        }
        argDialog = AlertDialog.Builder(context)
            .setTitle(plugin.name)
            .setMessage(plainText(label))
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.run(plugin, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_ARCHIVE = "archive"
        private const val KEY_RESULT = "result_key"

        /** The fragment result an applied change sends to the scene with this tag. */
        fun resultKey(sceneTag: String?) = "plugin_tags_applied:$sceneTag"

        /** Which archive: [archive]'s serverProfileId must be its source profile. */
        fun args(archive: Archive, resultKey: String) = Bundle().apply {
            putParcelable(KEY_ARCHIVE, archive)
            putString(KEY_RESULT, resultKey)
        }

        /** Plugin texts are written for the server's web pages and may hold HTML. */
        internal fun plainText(html: String): String =
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
}
