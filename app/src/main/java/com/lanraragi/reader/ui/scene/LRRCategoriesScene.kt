package com.lanraragi.reader.ui.scene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lanraragi.reader.ui.list.ListDiffBinder
import com.lanraragi.reader.ui.list.ListScreenState
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.scene.LRRCategoriesViewModel.CategoriesUiEvent
import com.lanraragi.reader.ui.collectFlow
import com.lanraragi.reader.domain.Category
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.widget.StateListLayout
import com.lanraragi.framework.scene.Announcer

/**
 * Scene that displays LANraragi categories with full CRUD support.
 * Pinned categories appear first. Clicking a category navigates
 * to GalleryListScene filtered by that category.
 *
 * Business logic (API calls) is delegated to [LRRCategoriesViewModel].
 * This Scene retains view construction, adapter, dialogs, and navigation.
 */
class LRRCategoriesScene : BaseScene() {

    private var mRecyclerView: RecyclerView? = null
    private var mStateList: StateListLayout? = null
    private var mToolbar: MaterialToolbar? = null

    // What the adapter shows; the categories observer is its single dispatch
    // path (every CRUD op re-fetches). See ListDiffBinder.
    private val mCategories = ListDiffBinder<Category> { old, new -> LRRCategoryDiffCallback(old, new) }
    private var mAdapter: CategoryAdapter? = null

    private lateinit var viewModel: LRRCategoriesViewModel

    override fun getNavCheckedItem(): Int = R.id.nav_favourite

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_lrr_categories, container, false)

        viewModel = ViewModelProvider(this)[LRRCategoriesViewModel::class.java]

        mToolbar = view.findViewById(R.id.toolbar)
        mStateList = view.findViewById<StateListLayout>(R.id.state_list).apply {
            onRetry = { viewModel.loadCategories() }
        }
        mRecyclerView = view.findViewById(R.id.state_content)

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

        mAdapter = CategoryAdapter().also { mCategories.adapter = it }
        mRecyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            adapter = mAdapter
        }

        // The screen's state: what the list shows, or loading / empty / the error.
        collectFlow(viewLifecycleOwner, viewModel.state) { state ->
            mCategories.submit(state.items)
            mStateList?.render(state)
        }

        // Observe one-shot UI events (success/error toasts)
        collectFlow(viewLifecycleOwner, viewModel.uiEvent) { event ->
            val ctx = ehContext ?: return@collectFlow
            when (event) {
                is CategoriesUiEvent.ShowError -> {
                    Toast.makeText(ctx, event.message, Toast.LENGTH_SHORT).show()
                }
                is CategoriesUiEvent.ShowSuccess -> {
                    Toast.makeText(ctx, event.messageResId, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // A rotation keeps the ViewModel and its state (an error stays with its
        // retry); a new view otherwise (first open, coming back to this
        // screen, a restore after process death) loads.
        if (savedInstanceState == null || viewModel.state.value is ListScreenState.Loading) {
            viewModel.loadCategories()
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mRecyclerView = null
        mStateList = null
        mToolbar = null
        mAdapter = null
        // Dispatch nowhere until the next view; its adapter shows the same items.
        mCategories.adapter = null
    }

    // ==================== CRUD Operations ====================

    /**
     * Show create/edit category dialog.
     * @param category null for create, non-null for edit.
     */
    private fun showCategoryDialog(category: Category?) {
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
                    viewModel.editCategory(category.id!!, name, search, pinned)
                } else {
                    viewModel.createCategory(name, search, pinned)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteCategory(category: Category) {
        val ctx = ehContext ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lrr_category_action_delete)
            .setMessage(getString(R.string.lrr_category_delete_confirm, category.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteCategory(category.id!!)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show long-press action menu for a category item.
     */
    private fun showCategoryActions(category: Category) {
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
                    2 -> viewModel.editCategory( // Pin/Unpin
                        category.id!!,
                        category.name!!,
                        category.search,
                        !category.isPinned()
                    )
                }
            }
            .show()
    }

    private fun onCategoryClick(category: Category) {
        val args = Bundle().apply {
            putString(GalleryListScene.KEY_ACTION, GalleryListScene.ACTION_LIST_URL_BUILDER)
        }

        // Static and dynamic categories alike open by id; see categoryListBuilder.
        val builder = categoryListBuilder(category) ?: return

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
            val cat = mCategories.items[position]

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
                holder.count.text = resources.getQuantityString(
                    R.plurals.lrr_category_archives, archiveCount, archiveCount
                )
            }
            holder.search.visibility = View.GONE

            // Uniform icon
            holder.icon.setImageResource(R.drawable.v_category_primary_x48)

            // Click to browse
            holder.itemView.setOnClickListener { onCategoryClick(cat) }

            // Long-press for actions menu
            holder.itemView.setOnLongClickListener {
                showCategoryActions(cat)
                true
            }
        }

        override fun getItemCount(): Int = mCategories.items.size
    }

    private class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.category_icon)
        val name: TextView = itemView.findViewById(R.id.category_name)
        val count: TextView = itemView.findViewById(R.id.category_count)
        val search: TextView = itemView.findViewById(R.id.category_search)
    }

    companion object {
        private const val TAG = "LRRCategoriesScene"
    }
}
