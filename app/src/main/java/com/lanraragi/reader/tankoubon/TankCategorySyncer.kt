package com.lanraragi.reader.tankoubon

import android.util.Log
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.data.LRRCategory
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankTagSyncFailedEvent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Best-effort static-category promotion after a membership change the app
 * performed (spec 2026-09-22 §6): fetch `/api/categories`, compute the
 * tank's category adds/removes with [TankCategoryPromotion], then one
 * `PUT` / `DELETE /api/categories/{cat}/{TANK_id}` per change.
 *
 * Same contract as [TankTagSyncer]: non-atomic, membership never rolled
 * back, a failed write posts [TankTagSyncFailedEvent] (kind CATEGORIES),
 * an unreachable categories fetch is silently false. Returns whether every
 * needed write landed.
 */
object TankCategorySyncer {

    /** Event sink; replaceable for tests. */
    internal var failureSink: (TankTagSyncFailedEvent) -> Unit = { AppEventBus.postTankTagSyncFailedEvent(it) }

    /** After [addedIds] joined [tankId]: the tank joins their static categories. */
    suspend fun afterAdd(client: OkHttpClient, baseUrl: String, tankId: String, tankName: String, addedIds: Collection<String>): Boolean =
        apply(client, baseUrl, tankId, tankName) { TankCategoryPromotion.onAdd(it, tankId, addedIds) }

    /** After [removedIds] left [tankId] whose members were [memberIdsBefore]: rule 3. */
    @Suppress("LongParameterList")
    suspend fun afterRemove(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        tankName: String,
        memberIdsBefore: Collection<String>,
        removedIds: Collection<String>,
    ): Boolean = apply(client, baseUrl, tankId, tankName) {
        TankCategoryPromotion.onRemove(it, tankId, memberIdsBefore, removedIds)
    }

    /** Before/after deleting the tank: remove the dangling id from every static category. */
    suspend fun onDissolve(client: OkHttpClient, baseUrl: String, tankId: String, tankName: String): Boolean =
        apply(client, baseUrl, tankId, tankName) { TankCategoryPromotion.onDissolve(it, tankId) }

    /** Reset: tank categories := union of [memberIds]' categories. */
    suspend fun reset(client: OkHttpClient, baseUrl: String, tankId: String, tankName: String, memberIds: Collection<String>): Boolean =
        apply(client, baseUrl, tankId, tankName) { TankCategoryPromotion.reset(it, tankId, memberIds) }

    /** Convenience for callers that only hold a [TankTagSyncer.Snapshot] (member ids = its keys). */
    suspend fun afterRemove(
        client: OkHttpClient,
        baseUrl: String,
        before: TankTagSyncer.Snapshot,
        removedIds: Collection<String>,
    ): Boolean = afterRemove(client, baseUrl, before.tankId, before.tankName, before.memberTags.keys, removedIds)

    private suspend fun apply(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        tankName: String,
        compute: (List<LRRCategory>) -> TankCategoryPromotion.Changes,
    ): Boolean {
        val categories = try {
            LRRCategoryApi.getCategories(client, baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            Log.w(TAG, "categories fetch failed; promotion skipped")
            return false
        }
        val changes = compute(categories)
        if (changes.isEmpty) return true
        var ok = true
        for (cat in changes.add) ok = ok and write(client, baseUrl, tankId, tankName) {
            LRRCategoryApi.addToCategory(client, baseUrl, cat, tankId)
        }
        for (cat in changes.remove) ok = ok and write(client, baseUrl, tankId, tankName) {
            LRRCategoryApi.removeFromCategory(client, baseUrl, cat, tankId)
        }
        return ok
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun write(client: OkHttpClient, baseUrl: String, tankId: String, tankName: String, op: suspend () -> Unit): Boolean =
        try {
            op()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            Log.w(TAG, "category write failed")
            failureSink(TankTagSyncFailedEvent(tankId, tankName, TankTagSyncFailedEvent.Kind.CATEGORIES))
            false
        }

    /** Tank name lookup for callers that only know the id (best-effort, "" on failure). */
    suspend fun nameOf(client: OkHttpClient, baseUrl: String, tankId: String): String = try {
        LRRTankoubonApi.getTankoubonFull(client, baseUrl, tankId).result.name
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        ""
    }

    private const val TAG = "TankCategorySyncer"
}
