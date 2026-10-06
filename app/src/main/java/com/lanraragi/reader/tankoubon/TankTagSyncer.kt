package com.lanraragi.reader.tankoubon

import android.util.Log
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankTagSyncFailedEvent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Best-effort materialization of a tank's own tags after a membership
 * change the app performed (spec 2026-09-22 §5.2–§5.3): fetch the tank's
 * `/full` (server truth for the tank tag string and every member's raw
 * tags), compute the new string with [TankTagMerge], and PUT it whole
 * (never `append` — removals subtract) only when it actually changed.
 *
 * Non-atomic by design: the membership request already succeeded; a
 * failure here posts [TankTagSyncFailedEvent] (Snackbar in the shell) and
 * never rolls membership back. Every entry point swallows non-cancellation
 * exceptions and returns whether a write landed.
 */
object TankTagSyncer {

    /** What a follow-up did: wrote the tank, found nothing to change, or could not finish. */
    enum class Outcome { WRITTEN, UNCHANGED, FAILED }

    /** Tank tags + member raw tags captured BEFORE a removal (rule 3 needs "before"). */
    class Snapshot(val tankId: String, val tankName: String, val tankTags: String?, val memberTags: Map<String, String>)

    /** Reads the tank's current tags and member tags, or null when the fetch fails (no event: nothing was written). */
    suspend fun snapshot(client: OkHttpClient, baseUrl: String, tankId: String): Snapshot? = try {
        toSnapshot(LRRTankoubonApi.getTankoubonFull(client, baseUrl, tankId).result)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        Log.w(TAG, "tank tag snapshot failed")
        null
    }

    fun toSnapshot(full: LRRTankoubonApi.TankoubonFull): Snapshot {
        val byId = full.fullData.associate { it.arcid to it.tags }
        // Member order = `archives`; ids without full_data contribute nothing.
        val members = LinkedHashMap<String, String>()
        for (id in full.archives) byId[id]?.let { members[id] = it }
        return Snapshot(full.id, full.name, full.tags, members)
    }

    /** After members were appended: `tank ∪ members` (the union over ALL current members ⊇ the new ones). */
    suspend fun afterAdd(client: OkHttpClient, baseUrl: String, tankId: String): Boolean =
        write(client, baseUrl, tankId) { snap -> TankTagMerge.onAdd(snap.tankTags, snap.memberTags.values.toList()) }

    /** After [removedIds] left the tank whose pre-removal state is [before]: rule 3. */
    suspend fun afterRemove(client: OkHttpClient, baseUrl: String, before: Snapshot, removedIds: Collection<String>): Boolean {
        val remaining = before.memberTags.filterKeys { it !in removedIds }.values.toList()
        val next = TankTagMerge.onRemove(before.tankTags, before.memberTags.values.toList(), remaining)
        return put(client, baseUrl, before, next)
    }

    /**
     * A member's own tags were edited ([oldTags] → [newTags]) in one tank
     * containing it: rule 3 with the member's old contribution as "before".
     * Unlike the other entry points this reports a failed snapshot too
     * ([Outcome.FAILED]): [TankTagOutbox] keeps such an edit for a retry,
     * since the old contribution cannot be recovered later. [notifyFailure]
     * false keeps a retry from posting the failure Snackbar again.
     */
    @Suppress("LongParameterList")
    suspend fun afterMemberTagsChanged(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        arcid: String,
        oldTags: String,
        newTags: String,
        notifyFailure: Boolean = true,
    ): Outcome {
        val snap = snapshot(client, baseUrl, tankId) ?: return Outcome.FAILED
        if (arcid !in snap.memberTags) return Outcome.UNCHANGED
        val before = snap.memberTags.mapValues { (id, tags) -> if (id == arcid) oldTags else tags }.values.toList()
        val after = snap.memberTags.mapValues { (id, tags) -> if (id == arcid) newTags else tags }.values.toList()
        return putOutcome(client, baseUrl, snap, TankTagMerge.onMemberTagsChanged(snap.tankTags, before, after), notifyFailure)
    }

    /** 「重置为成员并集」 (§5.4). */
    suspend fun reset(client: OkHttpClient, baseUrl: String, tankId: String): Boolean =
        write(client, baseUrl, tankId) { snap -> TankTagMerge.reset(snap.tankTags, snap.memberTags.values.toList()) }

    /**
     * First-open auto-fill (§4.5) on an already fetched [full]: when the tank
     * holds no non-excluded tags and has members, write the union once.
     * Returns the written string, or null when nothing was needed / the
     * write failed.
     */
    suspend fun autoFillIfNeeded(client: OkHttpClient, baseUrl: String, full: LRRTankoubonApi.TankoubonFull): String? {
        val snap = toSnapshot(full)
        if (!TankTagMerge.needsAutoFill(snap.tankTags, snap.memberTags.size)) return null
        val next = TankTagMerge.onAdd(snap.tankTags, snap.memberTags.values.toList())
        return if (put(client, baseUrl, snap, next)) next else null
    }

    private suspend fun write(
        client: OkHttpClient,
        baseUrl: String,
        tankId: String,
        compute: (Snapshot) -> String,
    ): Boolean {
        val snap = snapshot(client, baseUrl, tankId) ?: return false
        return put(client, baseUrl, snap, compute(snap))
    }

    /** PUTs [next] as the whole tag string when it differs; failures post the event. */
    private suspend fun put(client: OkHttpClient, baseUrl: String, snap: Snapshot, next: String): Boolean =
        putOutcome(client, baseUrl, snap, next, notifyFailure = true) == Outcome.WRITTEN

    private suspend fun putOutcome(
        client: OkHttpClient,
        baseUrl: String,
        snap: Snapshot,
        next: String,
        notifyFailure: Boolean,
    ): Outcome {
        if (TankTagMerge.split(next) == TankTagMerge.split(snap.tankTags)) return Outcome.UNCHANGED
        return try {
            LRRTankoubonApi.updateTankoubon(client, baseUrl, snap.tankId, tags = next)
            Outcome.WRITTEN
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            Log.w(TAG, "tank tag write failed")
            if (notifyFailure) {
                AppEventBus.postTankTagSyncFailedEvent(TankTagSyncFailedEvent(snap.tankId, snap.tankName))
            }
            Outcome.FAILED
        }
    }

    private const val TAG = "TankTagSyncer"
}
