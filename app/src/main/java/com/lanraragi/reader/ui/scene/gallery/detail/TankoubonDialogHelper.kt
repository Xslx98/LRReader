package com.lanraragi.reader.ui.scene.gallery.detail

import com.lanraragi.reader.tankoubon.TankListCache
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankMembershipChangedEvent
import com.lanraragi.reader.ui.scene.TankDialogs
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.tankoubon.TankCategorySyncer
import com.lanraragi.reader.tankoubon.TankNameSuggester
import com.lanraragi.reader.tankoubon.TankRecentStore
import com.lanraragi.reader.tankoubon.TankTagSyncer
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Tankoubon pickers, both on [TankPickerSheet] (docs/plan-2.4.0-tank-ux.md). Routes every call by the archive's SOURCE profile
 * via resolveSourceBaseUrl — never the active server. A definite 404 marks
 * the server pre-0.9.8 through [TankoubonSupportGate] and surfaces the
 * unsupported toast; 423 means the tank is locked server-side.
 */
object TankoubonDialogHelper {

    /**
     * Membership editor: the sheet in membership mode over the archive's
     * source server (checked = [arcid] currently in it, from the reverse
     * lookup; those tanks are pinned first). Apply writes the diffs via
     * add/removeFromTankoubon SEQUENTIALLY (server order is append order),
     * then [onChanged] gets the new membership ids. Creating a tank reopens
     * the sheet with the new tank checked and the pending ticks kept
     * ([pendingChecked]).
     */
    @JvmStatic
    fun showMembershipDialog(
        activity: Activity?,
        arcid: String?,
        serverProfileId: Long,
        archiveTitle: String? = null,
        pendingChecked: Set<String>? = null,
        onChanged: (List<String>) -> Unit,
    ) {
        if (activity == null || arcid.isNullOrEmpty()) return

        loadTanksAndMembership(activity, serverProfileId, arcid) { tanks, memberIds, serverUrl ->
            val mode = TankPickerSheet.Mode.Membership(
                arcid,
                checked = (pendingChecked ?: memberIds).toMutableSet(),
                onApply = { checkedIds ->
                    applyMembershipChanges(
                        activity, tanks,
                        checked = BooleanArray(tanks.size) { tanks[it].id in checkedIds },
                        originalChecked = BooleanArray(tanks.size) { tanks[it].id in memberIds },
                        arcid, serverProfileId, serverUrl, onChanged,
                    )
                },
            )
            TankPickerSheet(
                activity, serverUrl, tanks,
                recentIds = TankRecentStore.default.get(serverProfileId),
                suggestedName = TankNameSuggester.suggest(listOfNotNull(archiveTitle)),
                mode = mode,
            ) { name ->
                createTank(activity, serverUrl, name) { newId, _ ->
                    // Recent puts the new, pre-checked tank at the top of the reopened sheet.
                    TankRecentStore.default.touch(serverProfileId, newId)
                    showMembershipDialog(
                        activity, arcid, serverProfileId, archiveTitle,
                        pendingChecked = mode.checked + newId, onChanged = onChanged,
                    )
                }
            }.show()
        }
    }

    /**
     * Picker for batch ops: the sheet in pick mode over the server's tanks,
     * with "contains x/y" marks computed from [selectedArcids]. A server with
     * no tankoubon yet skips the sheet and goes straight to the create
     * prompt (spec 2026-09-21 §6). A pick or create also bumps the tank in
     * [TankRecentStore]. [onPicked] receives the picked
     * [LRRTankoubonApi.Tankoubon] as the picker saw it: `archives` empty
     * means the tank had zero members at pick time (a just-created tank
     * always does) so the batch can seed the tank cover after its first
     * successful add; `name` feeds the list's merge animation (provisional
     * tank row + Snackbar). A created tank is synthesized from the typed name.
     */
    @JvmStatic
    fun pickTankoubon(
        activity: Activity?,
        serverProfileId: Long,
        selectedArcids: List<String>,
        selectedTitles: List<String> = emptyList(),
        onPicked: (tank: LRRTankoubonApi.Tankoubon) -> Unit,
    ) {
        if (activity == null) return
        // Spec 2026-09-22 §9: "New tankoubon" pre-fills a name extracted from the selection.
        val suggested = TankNameSuggester.suggest(selectedTitles)
        val picked: (LRRTankoubonApi.Tankoubon) -> Unit = { tank ->
            TankRecentStore.default.touch(serverProfileId, tank.id)
            onPicked(tank)
        }
        val created: (String, String) -> Unit = { newId, name ->
            picked(LRRTankoubonApi.Tankoubon(id = newId, name = name))
        }

        loadTanksAndMembership(activity, serverProfileId, arcid = null) { tanks, _, serverUrl ->
            if (tanks.isEmpty()) {
                promptCreate(activity, serverUrl, suggested, created)
                return@loadTanksAndMembership
            }
            TankPickerSheet(
                activity, serverUrl, tanks,
                recentIds = TankRecentStore.default.get(serverProfileId),
                suggestedName = suggested,
                mode = TankPickerSheet.Mode.Pick(selectedArcids, picked),
            ) { name -> createTank(activity, serverUrl, name, created) }.show()
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
                    LRRTankoubonApi.getArchiveTankoubons(client, url, arcid).toSet()
                } else {
                    emptySet()
                }
                val tanks = mutableListOf<LRRTankoubonApi.Tankoubon>()
                // Server pages are 0-based (page 0 = parameterless request).
                var page = 0
                while (page < MAX_PAGES) {
                    val r = LRRTankoubonApi.getTankoubons(client, url, page)
                    tanks.addAll(r.result)
                    if (r.result.isEmpty() || tanks.size >= r.total) break
                    page++
                }
                if (arcid != null) TankoubonSupportGate.markSupported(url)
                TankListCache.put(url, tanks)
                Handler(Looper.getMainLooper()).post { onLoaded(tanks, memberIds, url) }
            } catch (ce: CancellationException) {
                // Lifecycle teardown cancelled the fetch; not an error to toast.
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    @Suppress("LongParameterList") // mirrors CategoryDialogHelper's apply-changes signature
    private fun applyMembershipChanges(
        activity: Activity,
        tanks: List<LRRTankoubonApi.Tankoubon>,
        checked: BooleanArray,
        originalChecked: BooleanArray,
        arcid: String,
        serverProfileId: Long,
        serverUrl: String,
        onChanged: (List<String>) -> Unit,
    ) {
        // App-scoped, not the Activity's lifecycleScope: this is a multi-step
        // server write (membership, then tag materialization and category
        // promotion); cutting it off on a rotation left the server
        // half-updated. Feedback goes through the app context and the
        // process-wide event bus; the Activity callback only runs if alive.
        val appContext = activity.applicationContext
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                val joined = mutableListOf<TankMembershipChangedEvent.JoinedTank>()
                val left = mutableListOf<String>()
                // Rule 3 needs the tank's PRE-removal tags and member tags
                // (spec 2026-09-22 §5.2): snapshot every tank being left first.
                val beforeRemoval = tanks.indices
                    .filter { originalChecked[it] && !checked[it] }
                    .mapNotNull { TankTagSyncer.snapshot(client, serverUrl, tanks[it].id) }
                // Sequential on purpose: the server appends members in call
                // order, and a locked tank should fail fast and visibly.
                for (i in tanks.indices) {
                    if (checked[i] == originalChecked[i]) continue
                    val tankId = tanks[i].id
                    if (checked[i]) {
                        LRRTankoubonApi.addToTankoubon(client, serverUrl, tankId, arcid)
                        val wasEmpty = tanks[i].archives.isEmpty()
                        if (wasEmpty) {
                            seedTankCoverBestEffort(client, serverUrl, tankId)
                        }
                        joined += TankMembershipChangedEvent.JoinedTank(tankId, tanks[i].name, wasEmpty)
                    } else {
                        LRRTankoubonApi.removeFromTankoubon(client, serverUrl, tankId, arcid)
                        left += tankId
                    }
                }
                // Materialize tank tags + promote static categories after the
                // membership writes (best-effort, spec 2026-09-22 §5 / §6).
                for (tank in joined) {
                    TankTagSyncer.afterAdd(client, serverUrl, tank.id)
                    TankCategorySyncer.afterAdd(client, serverUrl, tank.id, tank.name, listOf(arcid))
                }
                for (snap in beforeRemoval) {
                    TankTagSyncer.afterRemove(client, serverUrl, snap, listOf(arcid))
                    TankCategorySyncer.afterRemove(client, serverUrl, snap, listOf(arcid))
                }
                TankListCache.invalidate(serverUrl)
                for (tank in joined.asReversed()) TankRecentStore.default.touch(serverProfileId, tank.id)
                val newIds = tanks.indices.filter { checked[it] }.map { tanks[it].id }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, R.string.tank_op_done, Toast.LENGTH_SHORT).show()
                    if (joined.isNotEmpty() || left.isNotEmpty()) {
                        AppEventBus.postTankMembershipChangedEvent(
                            TankMembershipChangedEvent(arcid, joined, left)
                        )
                    }
                    if (!activity.isDestroyed) onChanged(newIds)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    /**
     * Best-effort cover seed for a tank that was EMPTY before the add above:
     * the server's append endpoint never queues thumbnail generation (only
     * the whole-list JSON PUT does), so without this the tank would serve
     * the placeholder cover forever. Mirrors the web client's behavior of
     * ending up with the first member's cover. Failures are swallowed — the
     * membership write already succeeded and the cover is cosmetic.
     */
    private suspend fun seedTankCoverBestEffort(
        client: OkHttpClient,
        serverUrl: String,
        tankId: String,
    ) {
        try {
            LRRTankoubonApi.updateTankThumbnail(client, serverUrl, tankId, globalPage1 = 1)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ignored: Exception) {
            // Cosmetic — see KDoc.
        }
    }

    /** Name dialog → createTankoubon → [onCreated] with the new tank id and the typed name. */
    private fun promptCreate(
        activity: Activity,
        serverUrl: String,
        suggestedName: String?,
        onCreated: (tankId: String, name: String) -> Unit,
    ) {
        TankDialogs.showNameInputDialog(
            activity, R.string.tank_create, suggestedName.orEmpty(), selectInitial = true,
        ) { name -> createTank(activity, serverUrl, name, onCreated) }
    }

    /** createTankoubon → [onCreated] with the new tank id and [name]. */
    private fun createTank(
        activity: Activity,
        serverUrl: String,
        name: String,
        onCreated: (tankId: String, name: String) -> Unit,
    ) {
        // App-scoped so a rotation cannot cut the create off (see
        // applyMembershipChanges); the follow-up needs a live Activity.
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                val client = ServiceRegistry.networkModule.okHttpClient
                val newId = LRRTankoubonApi.createTankoubon(client, serverUrl, name)
                TankListCache.invalidate(serverUrl)
                Handler(Looper.getMainLooper()).post {
                    if (!activity.isDestroyed) onCreated(newId, name)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    private fun postErrorToast(activity: Activity, serverUrl: String?, e: Exception) {
        val unsupported = serverUrl != null && TankoubonSupportGate.markFrom(serverUrl, e)
        val appContext = activity.applicationContext
        Handler(Looper.getMainLooper()).post {
            when {
                unsupported ->
                    Toast.makeText(appContext, R.string.tankoubons_unsupported, Toast.LENGTH_SHORT).show()
                e is LRRHttpException && e.code == HTTP_LOCKED ->
                    Toast.makeText(appContext, R.string.tank_locked, Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(appContext, friendlyError(appContext, e), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private const val MAX_PAGES = 100
    private const val HTTP_LOCKED = 423
}
