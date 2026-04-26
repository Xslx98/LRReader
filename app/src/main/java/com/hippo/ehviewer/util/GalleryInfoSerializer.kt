package com.hippo.ehviewer.util

import android.util.Log
import com.hippo.ehviewer.client.data.GalleryInfoEntity
import com.hippo.ehviewer.dao.DownloadInfo
import org.json.JSONObject

private const val TAG = "GalleryInfoSerializer"

/**
 * CSV serialization for [GalleryInfoEntity].
 *
 * Produces one CSV line (terminated with `\n`) whose columns match the
 * [galleryInfoFromCSV] parser.
 */
fun GalleryInfoEntity.toCSV(): String {
    return gid.toString() + "," +
        arcid + "," +
        title + "," +
        titleJpn + "," +
        thumb + "," +
        category + "," +
        posted + "," +
        uploader + "," +
        rating + "," +
        rated + "," +
        simpleLanguage + "," +
        simpleTags.contentToString() + "," +
        thumbWidth + "," +
        thumbHeight + "," +
        spanSize + "," +
        spanIndex + "," +
        spanGroupIndex + "," +
        favoriteSlot + "," +
        favoriteName + "," +
        pages + "\n"
}

/**
 * Parses a single CSV line (produced by [toCSV]) back into a
 * [GalleryInfoEntity]. Returns `null` when the line has fewer than 20
 * columns or contains unparseable numbers.
 */
fun galleryInfoFromCSV(csv: String): GalleryInfoEntity? {
    val values = csv.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    if (values.size < 20) {
        return null
    }
    val gi = GalleryInfoEntity()
    try {
        gi.gid = values[0].toLong()
        gi.arcid = values[1]
        gi.title = values[2]
        gi.titleJpn = values[3]
        gi.thumb = values[4]
        gi.category = values[5].toInt()
        gi.posted = values[6]
        gi.uploader = values[7]
        gi.rating = values[8].toFloat()
        gi.rated = values[9].toBoolean()
        gi.simpleLanguage = values[10]
        gi.simpleTags = values[11].substring(1, values[11].length - 1).split(", ".toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()
        gi.thumbWidth = values[12].toInt()
        gi.thumbHeight = values[13].toInt()
        gi.spanSize = values[14].toInt()
        gi.spanIndex = values[15].toInt()
        gi.spanGroupIndex = values[16].toInt()
        gi.favoriteSlot = values[17].toInt()
        gi.favoriteName = values[18]
        gi.pages = values[19].trim().toInt()
    } catch (e: NumberFormatException) {
        return null
    }
    return gi
}

/**
 * CSV serialization for [DownloadInfo].
 *
 * Emits the same 20-column wire format as [GalleryInfoEntity.toCSV] for
 * backward compatibility with users' existing CSV exports (read by
 * [galleryInfoFromCSV]). After W36-7 flatten DownloadInfo no longer
 * inherits the GalleryInfoEntity extension; this standalone keeps the
 * DownloadFragment export feature working without changing the on-disk
 * format.
 *
 * Fields not carried by the flattened DownloadInfo are filled with
 * defaults so the column count stays stable:
 *   - titleJpn / posted / uploader / favoriteName → null
 *   - category / thumbWidth / thumbHeight / spanSize / spanIndex /
 *     spanGroupIndex / pages → 0
 *   - rated → false
 *   - favoriteSlot → -2
 *
 * Format slimming will happen when GalleryInfoEntity retires (W36-11+).
 */
fun DownloadInfo.toCSV(): String {
    return gid.toString() + "," +
        arcid + "," +
        title + "," +
        "null" + "," +                   // titleJpn
        thumb + "," +
        "0" + "," +                      // category
        "null" + "," +                   // posted
        "null" + "," +                   // uploader
        rating + "," +
        "false" + "," +                  // rated
        simpleLanguage + "," +
        simpleTags.contentToString() + "," +
        "0,0,0,0,0," +                   // thumbWidth, thumbHeight, spanSize, spanIndex, spanGroupIndex
        "-2," +                          // favoriteSlot
        "null" + "," +                   // favoriteName
        "0\n"                            // pages
}

/**
 * Deserializes a [GalleryInfoEntity] from a [JSONObject] previously
 * produced by [GalleryInfoEntity.toJson].
 */
fun galleryInfoFromJson(obj: JSONObject): GalleryInfoEntity {
    val galleryInfo = GalleryInfoEntity()
    galleryInfo.posted = obj.optString("posted", null)
    galleryInfo.category = obj.optInt("category", 0)
    galleryInfo.favoriteName = obj.optString("favoriteName", null)
    galleryInfo.favoriteSlot = obj.optInt("favoriteSlot", 0)
    galleryInfo.gid = obj.optLong("gid", 0)
    galleryInfo.pages = obj.optInt("pages", 0)
    galleryInfo.rated = obj.optBoolean("rated", false)
    galleryInfo.rating = obj.optDouble("rating", 0.0).toFloat()
    galleryInfo.simpleLanguage = obj.optString("simpleLanguage", null)
    val simpleTagsArr = obj.optJSONArray("simpleTags")
    if (simpleTagsArr != null) {
        try {
            val tags = Array(simpleTagsArr.length()) { i ->
                simpleTagsArr.getString(i)
            }
            galleryInfo.simpleTags = tags
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize simpleTags from JSON", e)
        }
    }
    galleryInfo.spanGroupIndex = obj.optInt("spanGroupIndex", 0)
    galleryInfo.spanIndex = obj.optInt("spanIndex", 0)
    galleryInfo.spanSize = obj.optInt("spanSize", 0)
    val tgArray = obj.optJSONArray("tgList")
    if (tgArray != null) {
        try {
            val list = ArrayList<String>()
            for (i in 0 until tgArray.length()) {
                list.add(tgArray.getString(i))
            }
            galleryInfo.tgList = list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize tgList from JSON", e)
        }
    }
    galleryInfo.thumb = obj.optString("thumb", null)
    galleryInfo.thumbHeight = obj.optInt("thumbHeight", 0)
    galleryInfo.thumbWidth = obj.optInt("thumbWidth", 0)
    galleryInfo.title = obj.optString("title", null)
    galleryInfo.titleJpn = obj.optString("titleJpn", null)
    galleryInfo.arcid = obj.optString("token", null)
    galleryInfo.uploader = obj.optString("uploader", null)
    galleryInfo.serverProfileId = obj.optLong("serverProfileId", 0)
    return galleryInfo
}
