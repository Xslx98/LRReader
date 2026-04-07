package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.lrr.LRRAuthManager
import com.hippo.ehviewer.client.lrr.LRRCategoryApi
import com.hippo.ehviewer.client.lrr.data.LRRCategory
import com.hippo.ehviewer.client.lrr.friendlyError
import com.hippo.ehviewer.client.lrr.runSuspend
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.scene.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Scene that displays LANraragi categories with full CRUD support.
 * Pinned categories appear first. Clicking a category navigates
 * to GalleryListScene filtered by that category.
 */
class LRRCategoriesScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mProgress: View? = null
    private var mErrorView: View? = null
    private var mErrorIcon: ImageView? = null
    private var mErrorTitle: TextView? = null
    private var mErrorMessage: TextView? = null
    private var mErrorRetry: Button? = null
    private var mToolbar: MaterialToolbar? = null

    private val mCategories: MutableList<LRRCategory> = mutableListOf()
    private var mAdapter: CategoryAdapter? = null

    // Snapshot of the list last dispatched to the adapter. Read/written ONLY by
    // fetchCategories() (the single dispatch path — every CRUD op re-fetches).
    // See docs/diffutil-root-cause-analysis.md for the snapshot ownership rule.
    private var mLastSnapshot: List<LRRCategory> = emptyList()

    override fun getNavCheckedItem(): Int = R.id.nav_favourite

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_lrr_categories, container, false)

        mToolbar = view.findViewById(R.id.toolbar)
        mProgress = view.findViewById(R.id.progress)
        mErrorView = view.findViewById(R.id.error_view)
        mErrorIcon = view.findViewById(R.id.error_icon)
        mErrorTitle = view.findViewById(R.id.error_title)
        mErrorMessage = view.findViewById(R.id.error_message)
        mErrorRetry = view.findViewById(R.id.error_retry)
        mRecyclerView = view.findViewById(R.id.recycler_view)

        mToolbar?.apply {
            setTitle(R.string.lrr_categories_title)
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
            inflateMenu(R.menu.scene_lrr_categories)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_add_category) {
                    showCategoryDialog(null)
                    true
                } else {
                    false
                }
            }
        }

        mAdapter = CategoryAdapter()
        mRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = mAdapter
        }

        fetchCategories()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mProgress = null
        mErrorView = null
        mErrorIcon = null
        mErrorTitle = null
        mErrorMessage = null
        mErrorRetry = null
        mToolbar = null
        mAdapter = null
        // Reset snapshot so the next view recreation starts from a clean baseline.
        mLastSnapshot = emptyList()
    }

    // ==================== Data Loading ====================

    private fun fetchCategories() {
        showProgress()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = ehContext ?: return@launch
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient

                val categories = runSuspend {
                    LRRCategoryApi.getCategories(client, serverUrl)
                }

                // Sort: pinned first, then by name
                val pinned = mutableListOf<LRRCategory>()
                val unpinned = mutableListOf<LRRCategory>()
                for (cat in categories) {
                    if (cat.name.isNullOrEmpty()) continue
                    if (cat.isPinned()) {
                        pinned.add(cat)
                    } else {
                        unpinned.add(cat)
                    }
                }
                pinned.addAll(unpinned)

                activity?.runOnUiThread {
                    // Compute diff against the previously dispatched snapshot
                    // BEFORE mutating mCategories in place. Then swap in the
                    // new contents and update the snapshot, then dispatch.
                    val newList = ArrayList(pinned)
                    val adapter = mAdapter
                    if (adapter != null) {
                        val diff = DiffUtil.calculateDiff(
                            LRRCategoryDiffCallback(mLastSnapshot, newList)
                        )
                        mCategories.clear()
                        mCategories.addAll(newList)
                        mLastSnapshot = newList
                        diff.dispatchUpdatesTo(adapter)
                    } else {
                        mCategories.clear()
                        mCategories.addAll(newList)
                        mLastSnapshot = newList
                    }
                    if (mCategories.isEmpty()) {
                        showEmpty(getString(R.string.lrr_categories_empty))
                    } else {
                        showList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load categories", e)
                activity?.runOnUiThread {
                    showError(friendlyError(ehContext ?: return@runOnUiThread, e))
                }
            }
        }
    }

    // ==================== CRUD Operations ====================

    /**
     * Show create/edit category dialog.
     * @param category null for create, non-null for edit.
     */
    private fun showCategoryDialog(category: LRRCategory?) {
        val ctx = ehContext ?: return
        val isEdit = category != null

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_category_edit, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.category_name)
        val typeGroup = dialogView.findViewById<RadioGroup>(R.id.category_type)
        val searchLayout = dialogView.findViewById<View>(R.id.search_layout)
        val searchInput = dialogView.findViewById<EditText>(R.id.category_search)
        val pinnedSwitch = dialogView.findViewById<SwitchMaterial>(R.id.category_pinned)

        // Pre-fill for edit mode
        if (category != null) {
            nameInput.setText(category.name)
            pinnedSwitch.isChecked = category.isPinned()
            if (category.isDynamic()) {
                typeGroup.check(R.id.type_dynamic)
                searchLayout.visibility = View.VISIBLE
                searchInput.setText(category.search)
            }
            // Disable type switch for edit (can't change static<->dynamic if archives exist)
            if (category.archives.isNotEmpty()) {
                dialogView.findViewById<View>(R.id.type_static).isEnabled = false
                dialogView.findViewById<View>(R.id.type_dynamic).isEnabled = false
            }
        }

        // Toggle search field visibility
        typeGroup.setOnCheckedChangeListener { _, id ->
            searchLayout.visibility = if (id == R.id.type_dynamic) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(ctx)
            .setTitle(if (isEdit) R.string.lrr_category_edit else R.string.lrr_category_create)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, R.string.lrr_category_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val isDynamic = typeGroup.checkedRadioButtonId == R.id.type_dynamic
                val search = if (isDynamic) searchInput.text.toString().trim() else null
                val pinned = pinnedSwitch.isChecked

                if (category != null) {
                    updateCategory(category.id!!, name, search, pinned)
                } else {
                    createCategory(name, search, pinned)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createCategory(name: String, search: String?, pinned: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = ehContext ?: return@launch
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient
                runSuspend {
                    LRRCategoryApi.createCategory(client, serverUrl, name, search, pinned)
                }
                activity?.runOnUiThread {
                    Toast.makeText(ehContext, R.string.lrr_category_created, Toast.LENGTH_SHORT).show()
                    fetchCategories()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create category", e)
                activity?.runOnUiThread {
                    Toast.makeText(
                        ehContext,
                        friendlyError(ehContext ?: return@runOnUiThread, e),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateCategory(categoryId: String, name: String, search: String?, pinned: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ctx = ehContext ?: return@launch
                val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                val client = ServiceRegistry.networkModule.okHttpClient
                runSuspend {
                    LRRCategoryApi.updateCategory(client, serverUrl, categoryId, name, search, pinned)
                }
                activity?.runOnUiThread {
                    Toast.makeText(ehContext, R.string.lrr_category_updated, Toast.LENGTH_SHORT).show()
                    fetchCategories()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update category", e)
                activity?.runOnUiThread {
                    Toast.makeText(
                        ehContext,
                        friendlyError(ehContext ?: return@runOnUiThread, e),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun deleteCategory(category: LRRCategory) {
        val ctx = ehContext ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_category_action_delete)
            .setMessage(getString(R.string.lrr_category_delete_confirm, category.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val innerCtx = ehContext ?: return@launch
                        val serverUrl = LRRAuthManager.getServerUrl() ?: return@launch
                        val client = ServiceRegistry.networkModule.okHttpClient
                        runSuspend {
                            LRRCategoryApi.deleteCategory(client, serverUrl, category.id!!)
                        }
                        activity?.runOnUiThread {
                            Toast.makeText(ehContext, R.string.lrr_category_deleted, Toast.LENGTH_SHORT).show()
                            fetchCategories()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete category", e)
                        activity?.runOnUiThread {
                            Toast.makeText(
                                ehContext,
                                friendlyError(ehContext ?: return@runOnUiThread, e),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show long-press action menu for a category item.
     */
    private fun showCategoryActions(category: LRRCategory) {
        val ctx = ehContext ?: return

        val pinAction = if (category.isPinned()) {
            getString(R.string.lrr_category_action_unpin)
        } else {
            getString(R.string.lrr_category_action_pin)
        }

        val items = arrayOf(
            getString(R.string.lrr_category_action_edit),
            getString(R.string.lrr_category_action_delete),
            pinAction
        )

        AlertDialog.Builder(ctx)
            .setTitle(category.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showCategoryDialog(category) // Edit
                    1 -> deleteCategory(category) // Delete
                    2 -> updateCategory( // Pin/Unpin
                        category.id!!,
                        category.name!!,
                        category.search,
                        !category.isPinned()
                    )
                }
            }
            .show()
    }

    // ==================== View Helpers ====================

    private fun showProgress() {
        mProgress?.visibility = View.VISIBLE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.visibility = View.GONE
    }

    private fun showList() {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.VISIBLE
        mErrorView?.visibility = View.GONE
    }

    private fun showError(message: String) {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.apply {
            visibility = View.VISIBLE
            mErrorIcon?.visibility = View.VISIBLE
            mErrorTitle?.setText(R.string.lrr_error_title)
            mErrorMessage?.apply {
                visibility = View.VISIBLE
                text = message
            }
            mErrorRetry?.apply {
                visibility = View.VISIBLE
                setOnClickListener { fetchCategories() }
            }
        }
    }

    private fun showEmpty(message: String) {
        mProgress?.visibility = View.GONE
        mRecyclerView?.visibility = View.GONE
        mErrorView?.apply {
            visibility = View.VISIBLE
            mErrorIcon?.visibility = View.GONE
            mErrorTitle?.text = message
            mErrorMessage?.visibility = View.GONE
            mErrorRetry?.visibility = View.GONE
        }
    }

    private fun onCategoryClick(category: LRRCategory) {
        val args = Bundle().apply {
            putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_LIST_URL_BUILDER)
        }

        val builder = ListUrlBuilder().apply {
            mode = ListUrlBuilder.MODE_NORMAL
            keyword = if (category.isDynamic()) {
                // Dynamic category: use its search query as keyword
                category.search!!.trim()
            } else {
                // Static category: use category ID filter
                "category:${category.id}"
            }
        }

        args.putParcelable(GalleryListScene.KEY_LIST_URL_BUILDER, builder)
        startScene(Announcer(GalleryListScene::class.java).setArgs(args))
    }

    // ==================== Adapter ====================

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lrr_category, parent, false)
            return CategoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            val cat = mCategories[position]

            // Name (with pin indicator)
            if (cat.isPinned()) {
                holder.name.text = getString(R.string.lrr_category_pinned, cat.name)
            } else {
                holder.name.text = cat.name
            }

            // Archive count -- dynamic categories show "动态" label
            if (cat.isDynamic()) {
                holder.count.text = getString(R.string.lrr_category_label_dynamic)
            } else {
                val archiveCount = cat.archives.size
                holder.count.text = getString(R.string.lrr_category_archives, archiveCount)
            }
            holder.search.visibility = View.GONE

            // Uniform icon
            holder.icon.setImageResource(R.drawable.v_heart_primary_x48)

            // Click to browse
            holder.itemView.setOnClickListener { onCategoryClick(cat) }

            // Long-press for actions menu
            holder.itemView.setOnLongClickListener {
                showCategoryActions(cat)
                true
            }
        }

        override fun getItemCount(): Int = mCategories.size
    }

    private class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.category_icon)
        val name: TextView = itemView.findViewById(R.id.category_name)
        val count: TextView = itemView.findViewById(R.id.category_count)
        val search: TextView = itemView.findViewById(R.id.category_search)
    }

    /**
     * DiffUtil callback for LRRCategory lists. Identity is the category `id`
     * (LANraragi's CAT-prefixed string id). Content compares everything that
     * onBindViewHolder reads so the row repaints when name/pinned/search/
     * archive count change.
     */
    private class LRRCategoryDiffCallback(
        private val oldList: List<LRRCategory>,
        private val newList: List<LRRCategory>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldList[oldItemPosition]
            val n = newList[newItemPosition]
            return o.name == n.name &&
                o.isPinned() == n.isPinned() &&
                o.isDynamic() == n.isDynamic() &&
                o.search == n.search &&
                o.archives.size == n.archives.size
        }
    }

    companion object {
        private const val TAG = "LRRCategoriesScene"
    }
}
