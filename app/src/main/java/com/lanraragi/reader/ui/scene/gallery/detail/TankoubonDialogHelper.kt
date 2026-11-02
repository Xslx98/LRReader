package com.lanraragi.reader.ui.scene.gallery.detail

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ui.TankMembershipSyncFactory
import com.lanraragi.reader.ui.scene.TankDialogs
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.tankoubon.PickedTank
import com.lanraragi.reader.tankoubon.TankMembershipService
import com.lanraragi.reader.tankoubon.TankNameSuggester
import com.lanraragi.reader.tankoubon.TankRecentStore
import com.lanraragi.reader.tankoubon.TankRepository
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tankoubon pickers, both on [TankPickerSheet] (docs/plan-2.4.0-tank-ux.md). Routes every call by the archive's SOURCE profile
 * via ServerConnections.forProfile — never the active server. A definite 404 marks
 * the server pre-0.9.8 through [TankoubonSupportGate] and surfaces the
 * unsupported toast; 423 means the tank is locked server-side.
 */
object TankoubonDialogHelper {

    /** The picker's list load and create (it never calls [TankRepository.loadAll]). */
    private val repository by lazy { TankRepository(TankMembershipSyncFactory.runnerSafely()::sync) }

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

        loadTanksAndMembership(activity, serverProfileId, arcid) { loaded, conn ->
            val mode = TankPickerSheet.Mode.Membership(
                arcid,
                checked = (pendingChecked ?: loaded.memberIds).toMutableSet(),
                onApply = { checkedIds ->
                    applyMembershipChanges(activity, conn, arcid, loaded, checkedIds, onChanged)
                },
            )
            TankPickerSheet(
                activity, conn.baseUrl, loaded.tanks,
                recentIds = TankRecentStore.default.get(serverProfileId),
                suggestedName = TankNameSuggester.suggest(listOfNotNull(archiveTitle)),
                mode = mode,
            ) { name ->
                createTank(activity, conn, name) { newId, _ ->
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
     * [TankRecentStore]. [onPicked] receives the tank as the picker saw it:
     * `archives` empty means the tank had zero members at pick time (a
     * just-created tank always does) so the batch can seed the tank cover
     * after its first successful add; `name` feeds the list's merge
     * animation (provisional tank row + Snackbar). A created tank is
     * synthesized from the typed name.
     */
    @JvmStatic
    fun pickTankoubon(
        activity: Activity?,
        serverProfileId: Long,
        selectedArcids: List<String>,
        selectedTitles: List<String> = emptyList(),
        onPicked: (tank: PickedTank) -> Unit,
    ) {
        if (activity == null) return
        // Spec 2026-09-22 §9: "New tankoubon" pre-fills a name extracted from the selection.
        val suggested = TankNameSuggester.suggest(selectedTitles)
        val picked: (PickedTank) -> Unit = { tank ->
            TankRecentStore.default.touch(serverProfileId, tank.id)
            onPicked(tank)
        }
        val created: (String, String) -> Unit = { newId, name -> picked(PickedTank(id = newId, name = name)) }

        loadTanksAndMembership(activity, serverProfileId, arcid = null) { loaded, conn ->
            if (loaded.tanks.isEmpty()) {
                promptCreate(activity, conn, suggested, created)
                return@loadTanksAndMembership
            }
            TankPickerSheet(
                activity, conn.baseUrl, loaded.tanks,
                recentIds = TankRecentStore.default.get(serverProfileId),
                suggestedName = suggested,
                mode = TankPickerSheet.Mode.Pick(selectedArcids) { tank ->
                    picked(PickedTank(tank.id, tank.name, tank.archives))
                },
            ) { name -> createTank(activity, conn, name, created) }.show()
        }
    }

    /**
     * Shared front half of both dialogs: resolve the owning server, load
     * every tank plus, when [arcid] is given, its current membership
     * ([TankRepository.loadForPicker]), then hand off on the main thread.
     */
    private fun loadTanksAndMembership(
        activity: Activity,
        serverProfileId: Long,
        arcid: String?,
        onLoaded: (loaded: TankRepository.PickerList, conn: ServerConnection) -> Unit,
    ) {
        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            var serverUrl: String? = null
            try {
                val conn = ServerConnections.forProfile(serverProfileId)
                serverUrl = conn.baseUrl
                val loaded = repository.loadForPicker(conn, arcid)
                Handler(Looper.getMainLooper()).post { onLoaded(loaded, conn) }
            } catch (ce: CancellationException) {
                // Lifecycle teardown cancelled the fetch; not an error to toast.
                throw ce
            } catch (e: Exception) {
                postErrorToast(activity, serverUrl, e)
            }
        }
    }

    /**
     * Hands the diff to [TankMembershipService] (process-scoped, so closing
     * the sheet or rotating cannot cut the write off). Feedback goes through
     * the app context; the Activity callback only runs if it is alive.
     */
    @Suppress("LongParameterList")
    private fun applyMembershipChanges(
        activity: Activity,
        conn: ServerConnection,
        arcid: String,
        loaded: TankRepository.PickerList,
        checkedIds: Set<String>,
        onChanged: (List<String>) -> Unit,
    ) {
        val appContext = activity.applicationContext
        TankMembershipService.default.applyAsync(conn, arcid, loaded.tanks, loaded.memberIds, checkedIds) { result ->
            result.fold(
                onSuccess = { newIds ->
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, R.string.tank_op_done, Toast.LENGTH_SHORT).show()
                        if (!activity.isDestroyed) onChanged(newIds)
                    }
                },
                onFailure = { e -> postErrorToast(activity, conn.baseUrl, e as Exception) },
            )
        }
    }

    /** Name dialog → create → [onCreated] with the new tank id and the typed name. */
    private fun promptCreate(
        activity: Activity,
        conn: ServerConnection,
        suggestedName: String?,
        onCreated: (tankId: String, name: String) -> Unit,
    ) {
        TankDialogs.showNameInputDialog(
            activity, R.string.tank_create, suggestedName.orEmpty(), selectInitial = true,
        ) { name -> createTank(activity, conn, name, onCreated) }
    }

    /**
     * [TankRepository.createAsync] → [onCreated] with the new tank id and
     * [name]. The write runs on the repository's process scope so a rotation
     * cannot cut it off (see applyMembershipChanges); the follow-up needs a
     * live Activity.
     */
    private fun createTank(
        activity: Activity,
        conn: ServerConnection,
        name: String,
        onCreated: (tankId: String, name: String) -> Unit,
    ) {
        repository.createAsync(conn, name) { result ->
            result.fold(
                onSuccess = { newId ->
                    Handler(Looper.getMainLooper()).post {
                        if (!activity.isDestroyed) onCreated(newId, name)
                    }
                },
                onFailure = { e -> postErrorToast(activity, conn.baseUrl, e as Exception) },
            )
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

    private const val HTTP_LOCKED = 423
}
