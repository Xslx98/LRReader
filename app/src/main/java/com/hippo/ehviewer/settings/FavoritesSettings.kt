package com.hippo.ehviewer.settings

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.lib.yorozuya.AssertUtils

/**
 * Favorites-related settings extracted from Settings.java.
 * Covers favorite category names, counts, default slots.
 */
object FavoritesSettings {

    // --- Favorite Category Names ---
    private val KEYS_FAV_CAT = arrayOf(
        "fav_cat_0", "fav_cat_1", "fav_cat_2", "fav_cat_3", "fav_cat_4",
        "fav_cat_5", "fav_cat_6", "fav_cat_7", "fav_cat_8", "fav_cat_9"
    )
    private val DEFAULTS_FAV_CAT = arrayOf(
        "Favorites 0", "Favorites 1", "Favorites 2", "Favorites 3", "Favorites 4",
        "Favorites 5", "Favorites 6", "Favorites 7", "Favorites 8", "Favorites 9"
    )

    @JvmStatic
    fun getFavCat(): Array<String> {
        return Array(10) { i ->
            Settings.getString(KEYS_FAV_CAT[i], DEFAULTS_FAV_CAT[i]) ?: DEFAULTS_FAV_CAT[i]
        }
    }

    @JvmStatic
    fun putFavCat(value: Array<String>) {
        AssertUtils.assertEquals(10, value.size)
        val editor = Settings.getPreferences().edit()
        for (i in 0..9) {
            editor.putString(KEYS_FAV_CAT[i], value[i])
        }
        editor.apply()
    }

    // --- Favorite Counts ---
    private val KEYS_FAV_COUNT = arrayOf(
        "fav_count_0", "fav_count_1", "fav_count_2", "fav_count_3", "fav_count_4",
        "fav_count_5", "fav_count_6", "fav_count_7", "fav_count_8", "fav_count_9"
    )

    @JvmStatic
    fun getFavCount(): IntArray {
        return IntArray(10) { i ->
            Settings.getInt(KEYS_FAV_COUNT[i], 0)
        }
    }

    @JvmStatic
    fun putFavCount(count: IntArray) {
        AssertUtils.assertEquals(10, count.size)
        val editor = Settings.getPreferences().edit()
        for (i in 0..9) {
            editor.putInt(KEYS_FAV_COUNT[i], count[i])
        }
        editor.apply()
    }

    // --- Local/Cloud Fav Count ---
    private const val KEY_FAV_LOCAL = "fav_local"
    private const val KEY_FAV_CLOUD = "fav_cloud"

    @JvmStatic
    fun getFavLocalCount(): Int = Settings.getInt(KEY_FAV_LOCAL, 0)

    @JvmStatic
    fun putFavLocalCount(count: Int) = Settings.putInt(KEY_FAV_LOCAL, count)

    @JvmStatic
    fun getFavCloudCount(): Int = Settings.getInt(KEY_FAV_CLOUD, 0)

    @JvmStatic
    fun putFavCloudCount(count: Int) = Settings.putInt(KEY_FAV_CLOUD, count)

    // --- Recent Fav Cat ---
    private const val KEY_RECENT_FAV_CAT = "recent_fav_cat"

    @JvmStatic
    fun getRecentFavCat(): Int = Settings.getInt(KEY_RECENT_FAV_CAT, FavListUrlBuilder.FAV_CAT_ALL)

    @JvmStatic
    fun putRecentFavCat(value: Int) = Settings.putInt(KEY_RECENT_FAV_CAT, value)

    // --- Default Fav Slot ---
    private const val KEY_DEFAULT_FAV_SLOT = "default_favorite_2"
    const val INVALID_DEFAULT_FAV_SLOT = -2

    @JvmStatic
    fun getDefaultFavSlot(): Int = Settings.getInt(KEY_DEFAULT_FAV_SLOT, INVALID_DEFAULT_FAV_SLOT)

    @JvmStatic
    fun putDefaultFavSlot(value: Int) = Settings.putInt(KEY_DEFAULT_FAV_SLOT, value)
}
