package com.lanraragi.reader.ui.scene.download

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.download.DownloadState
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.gallery.TankMemberSeed
import com.lanraragi.reader.gallery.TankSeedStore
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tank download cards' controls on the Downloads page (Track 2). */
class DownloadTankCards(
    private val scene: DownloadsScene,
    private val viewModel: DownloadsViewModel,
    private val launchGallery: (Intent) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Open a tank card: rebuild the whole-tank composite session from the
     * PERSISTED group snapshot (works fully offline for downloaded
     * members; pagecounts ride the member rows' archive_json).
     */
    fun open(info: DownloadInfo) {
        val activity = scene.activity2 ?: return
        scene.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val members = withContext(ioDispatcher) {
                    ServiceRegistry.dataModule.downloadDbRepository
                        .getTankMemberArchives(info.arcid)
                }
                if (members.isEmpty()) return@launch
                val seed = TankSessionSeed(
                    tankId = info.arcid,
                    tankName = info.title.orEmpty(),
                    profileId = info.serverProfileId,
                    members = members.map { TankMemberSeed(it.arcid, it.title, it.pagecount) },
                )
                TankSeedStore.publish(seed)
                launchGallery(GalleryOpenHelper.buildTankReadIntent(activity, seed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open tank card ${info.arcid}", e)
            }
        }
    }

    /**
     * Card start control (spec 2026-09-21 §4): fill the tank through the
     * shared [TankFillDispatcher] — members already on disk are skipped,
     * partial / failed ones restart, and group ids WITHOUT a download row
     * (the INCOMPLETE case) get their metadata from the source server
     * before enqueueing. Unreachable metadata is reported, never silently
     * dropped; the group row is re-tagged with the full membership.
     */
    fun start(card: DownloadInfo) {
        val context = scene.ehContext ?: return
        val tankId = card.arcid
        val profileId = card.serverProfileId
        scene.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = ServiceRegistry.dataModule
                val (ids, resolved) = withContext(ioDispatcher) {
                    val ids = data.downloadDbRepository.getTankGroupMemberIds(tankId)
                    val present = data.downloadDbRepository.getTankMemberArchives(tankId)
                    val url = suspendRunCatching {
                        resolveSourceBaseUrl(profileId, data.profileLookupCache)
                    }.getOrNull()
                    val client = ServiceRegistry.networkModule.okHttpClient
                    ids to TankFillDispatcher.resolveMembers(ids, present) { id ->
                        if (url == null) {
                            null
                        } else {
                            suspendRunCatching {
                                LRRArchiveApi.getArchiveMetadata(client, url, id)
                                    .toArchive(sourceProfileId = profileId, sourceBaseUrl = url)
                            }.getOrNull()
                        }
                    }
                }
                val ctx = scene.ehContext ?: return@launch
                val dm = viewModel.downloadManager
                val plan = TankFillDispatcher.plan(resolved.members) { dm.getDownloadState(it) }
                TankFillDispatcher.dispatch(ctx, dm, plan, tankId, card.title.orEmpty(), profileId, ids)
                val resources = scene.resources
                Toast.makeText(ctx, TankFillDispatcher.feedback(resources, plan), Toast.LENGTH_SHORT).show()
                if (resolved.unresolved.isNotEmpty()) {
                    val n = resolved.unresolved.size
                    Toast.makeText(
                        ctx, resources.getQuantityString(R.plurals.tank_fill_unreachable, n, n), Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tank card", e)
                Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Card stop control: halt every member still queued or downloading. */
    fun stop(card: DownloadInfo) {
        val active = viewModel.tankMembersOf(card.arcid)
            .filter { it.state == DownloadState.WAIT || it.state == DownloadState.DOWNLOAD }
            .map { it.arcid }
        if (active.isNotEmpty()) viewModel.stopRangeDownloads(active)
    }

    private companion object {
        val TAG: String = DownloadsScene::class.java.simpleName
    }
}
