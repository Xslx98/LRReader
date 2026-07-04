package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.data.LRRCategory
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Encapsulates the LANraragi category selection dialog logic.
 * Extracted from GalleryDetailScene.onClick(mHeartGroup).
 */
object CategoryDialogHelper {

    /**
     * Callback for when the favorite status changes after category operations.
     */
    fun interface Callback {
        fun onFavoriteStatusChanged(isFavorited: Boolean, favoriteName: String?)
    }

    /**
     * Show the category selection dialog for an [arcid]. Loads categories
     * from the server, presents a checkbox list, and applies changes on
     * confirmation. Useful from any context (detail page, gallery list
     * long-press, shortcuts).
     *
     * @param serverProfileId source profile that owns the archive; the dialog
     *   reads and writes categories on THAT server, not the active one.
     *   Categories live per-server and arcids are content-hash based, so
     *   editing via the active server would touch the wrong one for a
     *   cross-server archive. Pass 0 to fall back to the active profile.
     */
    @JvmStatic
    fun showCategoryDialog(activity: Activity?, arcid: String?, serverProfileId: Long, callback: Callback?) {
        if (activity == null || arcid.isNullOrEmpty()) return

        loadStaticCategories(activity, serverProfileId) { staticCats, serverUrl ->
            val names = categoryDisplayNames(staticCats)
            val checked = BooleanArray(staticCats.size) { i ->
                staticCats[i].archives?.contains(arcid) == true
            }
            val originalChecked = checked.clone()
            showCategoryCheckboxDialog(
                activity, staticCats, names, checked, originalChecked,
                arcid, serverUrl, callback
            )
        }
    }

    /**
     * Pick-only variant for batch operations: loads static categories from the
     * server owning [serverProfileId] and presents a single-choice list. The
     * caller performs whatever add operation it needs via [onPicked]; nothing
     * is written here. Same loading/empty/error toasts as [showCategoryDialog].
     */
    @JvmStatic
    fun pickStaticCategory(activity: Activity?, serverProfileId: Long, onPicked: (categoryId: String) -> Unit) {
        if (activity == null) return

        loadStaticCategories(activity, serverProfileId) { staticCats, _ ->
            AlertDialog.Builder(activity)
                .setTitle(R.string.lrr_add_to_category)
                .setItems(categoryDisplayNames(staticCats)) { _, which ->
                    staticCats[which].id?.let(onPicked)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Shared front half of both dialogs: resolve the owning server's base URL,
     * fetch its categories on IO, filter to static ones, then hand the result
     * to [onLoaded] on the main thread. Empty result and fetch errors surface
     * as toasts and [onLoaded] is not called.
     */
    private fun loadStaticCategories(
        activity: Activity,
        serverProfileId: Long,
        onLoaded: (staticCats: List<LRRCategory>, serverUrl: String) -> Unit,
    ) {
        if (LRRAuthManager.getServerUrl() == null) return

        Toast.makeText(activity, R.string.lrr_loading_categories, Toast.LENGTH_SHORT).show()

        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverUrl = resolveSourceBaseUrl(
                    serverProfileId,
                    ServiceRegistry.dataModule.profileLookupCache,
                )
                val client = ServiceRegistry.networkModule.okHttpClient
                val categories = runSuspend {
                    LRRCategoryApi.getCategories(client, serverUrl)
                }

                // Filter to static categories only
                val staticCats = categories.filter { it.search.isNullOrEmpty() }

                if (staticCats.isEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(activity, R.string.lrr_no_static_categories, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Handler(Looper.getMainLooper()).post {
                    onLoaded(staticCats, serverUrl)
                }
            } catch (ce: CancellationException) {
                // Lifecycle teardown cancelled the fetch; not an error to toast.
                throw ce
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, friendlyError(activity, e), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun categoryDisplayNames(staticCats: List<LRRCategory>): Array<String> =
        Array(staticCats.size) { i ->
            val cat = staticCats[i]
            "${cat.name} (${cat.archives?.size ?: 0})"
        }

    private fun showCategoryCheckboxDialog(
        activity: Activity, staticCats: List<LRRCategory>,
        names: Array<String>, checked: BooleanArray, originalChecked: BooleanArray,
        arcid: String, serverUrl: String, callback: Callback?
    ) {
        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, pad / 2)

        for (i in staticCats.indices) {
            val cb = CheckBox(activity)
            cb.text = names[i]
            cb.isChecked = checked[i]
            val idx = i
            cb.setOnCheckedChangeListener { _, isChecked -> checked[idx] = isChecked }
            container.addView(cb)
        }

        val scrollView = ScrollView(activity)
        scrollView.addView(container)
        val screenH = activity.resources.displayMetrics.heightPixels
        scrollView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            minOf(FrameLayout.LayoutParams.WRAP_CONTENT, (screenH * 0.6).toInt())
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_add_to_category)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyCategoryChanges(
                    activity, staticCats, checked, originalChecked,
                    arcid, serverUrl, callback
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.lrr_category_create_inline) { _, _ ->
                showCreateCategoryDialog(activity, arcid, serverUrl, callback)
            }
            .show()
    }

    private fun applyCategoryChanges(
        activity: Activity, staticCats: List<LRRCategory>,
        checked: BooleanArray, originalChecked: BooleanArray,
        arcid: String, serverUrl: String, callback: Callback?
    ) {
        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            try {
                val c = ServiceRegistry.networkModule.okHttpClient
                for (i in staticCats.indices) {
                    if (checked[i] != originalChecked[i]) {
                        val catId = staticCats[i].id!!
                        if (checked[i]) {
                            runSuspend {
                                LRRCategoryApi.addToCategory(c, serverUrl, catId, arcid)
                            }
                        } else {
                            runSuspend {
                                LRRCategoryApi.removeFromCategory(c, serverUrl, catId, arcid)
                            }
                        }
                    }
                }

                val newFavNames = ArrayList<String>()
                for (i in staticCats.indices) {
                    if (checked[i]) {
                        newFavNames.add(staticCats[i].name ?: "")
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (callback != null) {
                        val isFav = newFavNames.isNotEmpty()
                        val favName = when {
                            newFavNames.isEmpty() -> null
                            newFavNames.size == 1 -> newFavNames[0]
                            else -> newFavNames[0] +
                                activity.getString(R.string.lrr_category_info_suffix) +
                                newFavNames.size +
                                activity.getString(R.string.lrr_category_count_suffix)
                        }
                        callback.onFavoriteStatusChanged(isFav, favName)
                    }
                    Toast.makeText(activity, R.string.lrr_category_updated_toast, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, friendlyError(activity, e), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCreateCategoryDialog(
        activity: Activity, arcid: String, serverUrl: String, callback: Callback?
    ) {
        val input = EditText(activity)
        input.setHint(R.string.lrr_category_name_hint)
        input.isSingleLine = true
        val px = (24 * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(activity)
        frame.setPadding(px, px / 2, px, 0)
        frame.addView(input)

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_category_create)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val catName = input.text.toString().trim()
                if (catName.isEmpty()) {
                    Toast.makeText(activity, R.string.lrr_category_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val c = ServiceRegistry.networkModule.okHttpClient
                        val newCatId = runSuspend {
                            LRRCategoryApi.createCategory(c, serverUrl, catName, null, false)
                        }
                        runSuspend {
                            LRRCategoryApi.addToCategory(c, serverUrl, newCatId, arcid)
                        }

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(activity, R.string.lrr_category_created, Toast.LENGTH_SHORT).show()
                            callback?.onFavoriteStatusChanged(true, catName)
                        }
                    } catch (ex: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(activity, friendlyError(activity, ex), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
