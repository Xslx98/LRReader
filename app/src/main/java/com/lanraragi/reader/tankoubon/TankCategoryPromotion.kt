package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.data.LRRCategory

/**
 * Pure rules for STATIC-category promotion (spec 2026-09-22 §6): a
 * tankoubon follows its members into the static categories they belong
 * to, so a category keeps showing the book after its chapters were merged
 * (upstream drops both the member and the tank from a category view once
 * the member is tank-grouped). Dynamic categories are ignored — they match
 * server-side through the tank's own and imputed tags.
 *
 * Mirrors [TankTagMerge]: add = union, remove = rule 3
 * (`manualCats = cats(tank) − ⋃ cats(members before)`), reset recomputes,
 * dissolve clears. Members always STAY in their own categories.
 */
object TankCategoryPromotion {

    /** Category ids to `PUT` the tank into and to `DELETE` it from, in category order. */
    data class Changes(val add: List<String>, val remove: List<String>) {
        val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()

        companion object {
            val NONE = Changes(emptyList(), emptyList())
        }
    }

    /** A static category with its member ids as a set (lists hold up to ~100k ids). */
    private class StaticCat(val id: String, val members: Set<String>)

    /** Static categories with an id, in server order. */
    private fun static(categories: List<LRRCategory>): List<StaticCat> =
        categories.mapNotNull { cat ->
            val id = cat.id
            if (cat.isDynamic() || id.isNullOrEmpty()) null else StaticCat(id, cat.archives.toHashSet())
        }

    /** Static category ids containing [id]. */
    fun categoriesOf(categories: List<LRRCategory>, id: String): List<String> =
        static(categories).filter { id in it.members }.map { it.id }

    /** Add member(s): the tank joins every static category containing a new member that it is not in yet. */
    fun onAdd(categories: List<LRRCategory>, tankId: String, newMemberIds: Collection<String>): Changes {
        val add = static(categories)
            .filter { cat -> tankId !in cat.members && newMemberIds.any { it in cat.members } }
            .map { it.id }
        return Changes(add, emptyList())
    }

    /**
     * Remove member(s), rule 3: categories the tank is in that no member
     * before the removal explained are manual and stay; every other tank
     * category with no remaining member loses the tank.
     */
    fun onRemove(
        categories: List<LRRCategory>,
        tankId: String,
        memberIdsBefore: Collection<String>,
        removedIds: Collection<String>,
    ): Changes {
        val remaining = memberIdsBefore.filter { it !in removedIds }
        val remove = static(categories)
            .filter { cat ->
                tankId in cat.members &&
                    memberIdsBefore.any { it in cat.members } &&
                    remaining.none { it in cat.members }
            }
            .map { it.id }
        return Changes(emptyList(), remove)
    }

    /** Dissolve (delete tank): upstream leaves the id dangling — the client removes it everywhere. */
    fun onDissolve(categories: List<LRRCategory>, tankId: String): Changes =
        Changes(emptyList(), categoriesOf(categories, tankId))

    /** Reset: tank categories := ⋃ cats(current members) — add the missing, remove the others. */
    fun reset(categories: List<LRRCategory>, tankId: String, memberIds: Collection<String>): Changes {
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        for (cat in static(categories)) {
            val hasTank = tankId in cat.members
            val hasMember = memberIds.any { it in cat.members }
            when {
                hasMember && !hasTank -> add.add(cat.id)
                !hasMember && hasTank -> remove.add(cat.id)
            }
        }
        return Changes(add, remove)
    }
}
