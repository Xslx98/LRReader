package com.lanraragi.reader.ui.scene.tankdetail

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hippo.android.resource.AttrResources
import com.lanraragi.framework.widget.LoadImageViewNew
import com.lanraragi.reader.R
import com.lanraragi.reader.client.ArchiveCoverStamps
import com.lanraragi.reader.client.LRRCacheKeyFactory
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.tankoubon.TankMemberStrip

/**
 * Horizontal members strip of the tank detail page (spec 2026-09-22
 * §4.6): the first [TankMemberStrip.MAX_COVERS] members as cover cards
 * plus one "+N" tail card. Greyed members (missing from a downloaded
 * tank) render at reduced alpha. Every cover load goes through
 * [LRRCacheKeyFactory.getThumbKey] + [ArchiveCoverStamps.bust] so a cover
 * changed elsewhere in the app revalidates here too.
 */
internal class TankMemberStripAdapter(
    private val onMemberClick: (Archive) -> Unit,
    private val onMemberLongClick: (Archive, View) -> Unit,
    private val onMoreClick: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var members: List<Archive> = emptyList()
    private var greyed: Set<String> = emptySet()
    private var plan = TankMemberStrip.plan(0)

    /** Replaces the strip contents; [greyedIds] = members to render dimmed. */
    fun submit(newMembers: List<Archive>, greyedIds: Set<String>) {
        members = newMembers
        greyed = greyedIds
        plan = TankMemberStrip.plan(newMembers.size)
        // The strip is at most 9 cards; a full rebind is the honest diff.
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = plan.shown + if (plan.hasMore) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position < plan.shown) TYPE_MEMBER else TYPE_MORE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MEMBER) {
            MemberHolder(inflater.inflate(R.layout.item_tank_detail_member, parent, false))
        } else {
            MoreHolder(inflater.inflate(R.layout.item_tank_detail_more, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is MemberHolder -> {
                val member = members[position]
                holder.title.text = member.title
                holder.thumb.load(
                    LRRCacheKeyFactory.getThumbKey(member.arcid),
                    ArchiveCoverStamps.bust(member.thumbnailUrl, member.arcid),
                )
                holder.itemView.alpha = if (member.arcid in greyed) GREYED_ALPHA else 1f
                holder.itemView.setOnClickListener { onMemberClick(member) }
                holder.itemView.setOnLongClickListener {
                    onMemberLongClick(member, it)
                    true
                }
            }
            is MoreHolder -> {
                holder.more.text = holder.more.context.getString(R.string.tank_member_more, plan.more)
                holder.itemView.setOnClickListener { onMoreClick() }
            }
        }
    }

    private class MemberHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: LoadImageViewNew = view.findViewById(R.id.member_thumb)
        val title: TextView = view.findViewById(R.id.member_title)
    }

    private class MoreHolder(view: View) : RecyclerView.ViewHolder(view) {
        val more: TextView = view.findViewById(R.id.member_more)

        init {
            val density = view.resources.displayMetrics.density
            more.background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
                setColor(AttrResources.getAttrColor(view.context, R.attr.tagGroupBackgroundColor))
            }
        }
    }

    private companion object {
        const val TYPE_MEMBER = 0
        const val TYPE_MORE = 1
        const val GREYED_ALPHA = 0.38f
        const val CORNER_DP = 6f
    }
}
