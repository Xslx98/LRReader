package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.ui.scene.TankDialogs
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tankoubon membership dialogs (structural clone of [CategoryDialogHelper]
 * with the API swapped). Routes every call by the archive's SOURCE profile
 * via resolveSourceBaseUrl — never the active server. A definite 404 marks
 * the server pre-0.9.8 through [TankoubonSupportGate] and surfaces the
 * unsupported toast; 423 means the tank is locked server-side.
 */
object TankoubonDialogHelper {

    /**
     * Membership editor: checkbox per tank on the archive's source server
     * (checked = [arcid] currently in it, from the reverse lookup), OK
     * applies diffs via add/removeFromTankoubon SEQUENTIALLY (server order
     * is append order), then [onChanged] gets the new membership ids.
     * Neutral button creates a tank, then reopens this dialog.
     */
    @JvmStatic
    fun showMembershipDialog(
        activity: Activity?,
        arcid: String?,
        serverProfileId: Long,
        onChanged: (List<String>) -> Unit,
    ) {
        if (activity == null || arcid.isNullOrEmpty()) return

        loadTanksAndMembership(activity, serverProfileId, arcid) { tanks, memberIds, serverUrl ->
            val checked = BooleanArray(tanks.size) { i -> tanks[i].id in memberIds }
            showMembershipCheckboxDialog(
                activity, tanks, checked, checked.clone(),
                arcid, serverProfileId, serverUrl, onChanged
            )
        }
    }

    /**
     * Simple picker for batch ops: single-choice list of the server's tanks
     * with a leading "create" row (inline create then pick). Empty list →
     * [R.string.tank_none] toast.
     */
    @JvmStatic
    fun pickTankoubon(activity: Activity?, serverProfileId: Long, onPicked: (tankId: String) -> Unit) {
        if (activity == null) return

        loadTanksAndMembership(activity, serverProfileId, arcid = null) { tanks, _, serverUrl ->
            if (tanks.isEmpty()) {
                Toast.makeText(activity, R.string.tank_none, Toast.LENGTH_SHORT).show()
                return@loadTanksAndMembership
            }
            val items = arrayOf(activity.getString(R.string.tank_create)) +
                tankDisplayNames(tanks)
            AlertDialog.Builder(activity)
                .setTitle(R.string.tank_add_to)
                .setItems(items) { _, which ->
                    if (which == 0) {
                        promptCreate(activity, serverUrl, onPicked)
                    } else {
                        onPicked(tanks[which - 1].id)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Shared front half of both dialogs: resolve the owning server's base
     * URL, fetch every tank (list endpoint page loop) plus, when [arcid] is
     * given, its current membership, then hand off on the main thread. Only
     * the reverse lookup is a 0.9.8-only route, so success with an arcid
     * also marks the server supported.
     */
    private fun loadTanksAndMembership(
        activity: Activity,
        serverProfileId: Long,
        arcid: String?,
        onLoaded: (tanks: List<LRRTankoubonApi.Tankoubon>, memberIds: Set<String>, serverUrl: String) -> Unit,
    ) {
        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            var serverUrl: String? = null
            try {
                serverUrl = resolveSourceBaseUrl(
                    serverProfileId,
                    ServiceRegistry.dataModule.profileLookupCache,
                )
                val url = serverUrl
                val client = ServiceRegistry.networkModule.okHttpClient
                val memberIds = if (arcid != null) {
                    runSuspend { LRRTankoubonApi.getArchiveTankoubons(client, url, arcid) }.toSet()
                } else {
                    emptySet()
                }
                val tanks = mutableListOf<LRRTankoubonApi.Tankoubon>()
                // Server pages are 0-based (page 0 = parameterless request).
                var page = 0
                while (page < MAX_PAGES) {
                    val r = runSuspend { LRRTankoubonApi.getTankoubons(client, url, page) }
                    tanks.addAll(r.result)
                    if (r.result.isEmpty() || tanks.size >= r.total) break
                    page++
                }
                if (arcid != null) TankoubonSupportGate.markSupported(url)
                Handler(Looper.getMainLooper()).post { onLoaded(tanks, memberIds, url) }
            } catch (ce: CancellationException) {
                // Lifecycle teardown cancelled the fetch; not an error to toast.
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    private fun tankDisplayNames(tanks: List<LRRTankoubonApi.Tankoubon>): Array<String> =
        Array(tanks.size) { i -> "${tanks[i].name} (${tanks[i].archives.size})" }

    @Suppress("LongParameterList") // mirrors CategoryDialogHelper's checkbox-dialog signature
    private fun showMembershipCheckboxDialog(
        activity: Activity,
        tanks: List<LRRTankoubonApi.Tankoubon>,
        checked: BooleanArray,
        originalChecked: BooleanArray,
        arcid: String,
        serverProfileId: Long,
        serverUrl: String,
        onChanged: (List<String>) -> Unit,
    ) {
        val container = LinearLayout(activity)
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, pad / 2)

        val names = tankDisplayNames(tanks)
        for (i in tanks.indices) {
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
            .setTitle(R.string.tank_add_to)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyMembershipChanges(
                    activity, tanks, checked, originalChecked,
                    arcid, serverUrl, onChanged
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.tank_create) { _, _ ->
                promptCreate(activity, serverUrl) {
                    // Reopen so the fresh tank shows up in the checkbox list.
                    showMembershipDialog(activity, arcid, serverProfileId, onChanged)
                }
            }
            .show()
    }

    @Suppress("LongParameterList") // mirrors CategoryDialogHelper's apply-changes signature
    private fun applyMembershipChanges(
        activity: Activity,
        tanks: List<LRRTankoubonApi.Tankoubon>,
        checked: BooleanArray,
        originalChecked: BooleanArray,
        arcid: String,
        serverUrl: String,
        onChanged: (List<String>) -> Unit,
    ) {
        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                // Sequential on purpose: the server appends members in call
                // order, and a locked tank should fail fast and visibly.
                for (i in tanks.indices) {
                    if (checked[i] == originalChecked[i]) continue
                    val tankId = tanks[i].id
                    if (checked[i]) {
                        runSuspend { LRRTankoubonApi.addToTankoubon(client, serverUrl, tankId, arcid) }
                    } else {
                        runSuspend { LRRTankoubonApi.removeFromTankoubon(client, serverUrl, tankId, arcid) }
                    }
                }
                val newIds = tanks.indices.filter { checked[it] }.map { tanks[it].id }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, R.string.tank_op_done, Toast.LENGTH_SHORT).show()
                    onChanged(newIds)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    /** Name dialog → createTankoubon → [onCreated] with the new tank id. */
    private fun promptCreate(activity: Activity, serverUrl: String, onCreated: (tankId: String) -> Unit) {
        TankDialogs.showNameInputDialog(activity, R.string.tank_create, "") { name ->
            (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = ServiceRegistry.networkModule.okHttpClient
                    val newId = runSuspend { LRRTankoubonApi.createTankoubon(client, serverUrl, name) }
                    Handler(Looper.getMainLooper()).post { onCreated(newId) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    postErrorToast(activity, serverUrl, e)
                }
            }
        }
    }

    private fun postErrorToast(activity: Activity, serverUrl: String?, e: Exception) {
        val unsupported = serverUrl != null && TankoubonSupportGate.markFrom(serverUrl, e)
        Handler(Looper.getMainLooper()).post {
            when {
                unsupported ->
                    Toast.makeText(activity, R.string.tankoubons_unsupported, Toast.LENGTH_SHORT).show()
                e is LRRHttpException && e.code == HTTP_LOCKED ->
                    Toast.makeText(activity, R.string.tank_locked, Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(activity, friendlyError(activity, e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private const val MAX_PAGES = 100
    private const val HTTP_LOCKED = 423
}
