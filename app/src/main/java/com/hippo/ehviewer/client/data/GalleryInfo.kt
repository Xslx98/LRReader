/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client.data

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Ignore
import com.hippo.ehviewer.dao.DownloadInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

open class GalleryInfo : Parcelable {

    @JvmField
    @ColumnInfo(name = "GID")
    var gid: Long = 0

    @JvmField
    @ColumnInfo(name = "TOKEN")
    var token: String? = null

    @JvmField
    @ColumnInfo(name = "TITLE")
    var title: String? = null

    @JvmField
    @ColumnInfo(name = "TITLE_JPN")
    var titleJpn: String? = null

    @JvmField
    @ColumnInfo(name = "THUMB")
    var thumb: String? = null

    @JvmField
    @ColumnInfo(name = "CATEGORY")
    var category: Int = 0

    @JvmField
    @ColumnInfo(name = "POSTED")
    var posted: String? = null

    @JvmField
    @ColumnInfo(name = "UPLOADER")
    var uploader: String? = null

    @JvmField
    @ColumnInfo(name = "RATING")
    var rating: Float = 0f

    @JvmField
    @Ignore
    var rated: Boolean = false

    @JvmField
    @Ignore
    var simpleTags: Array<String>? = null

    @JvmField
    @Ignore
    var pages: Int = 0

    /**
     * Server-reported reading progress (1-indexed page number).
     * 0 means unread.
     */
    @JvmField
    @Ignore
    var progress: Int = 0

    @JvmField
    @Ignore
    var thumbWidth: Int = 0

    @JvmField
    @Ignore
    var thumbHeight: Int = 0

    @JvmField
    @Ignore
    var spanSize: Int = 0

    @JvmField
    @Ignore
    var spanIndex: Int = 0

    @JvmField
    @Ignore
    var spanGroupIndex: Int = 0

    @JvmField
    @Ignore
    var tgList: ArrayList<String>? = null

    @JvmField
    @ColumnInfo(name = "SIMPLE_LANGUAGE")
    var simpleLanguage: String? = null

    @JvmField
    @Ignore
    var favoriteSlot: Int = -2

    @JvmField
    @Ignore
    var favoriteName: String? = null

    /**
     * ID of the ServerProfile this entry belongs to.
     * Used to filter history/downloads by server.
     */
    @JvmField
    @ColumnInfo(name = "SERVER_PROFILE_ID", defaultValue = "0")
    var serverProfileId: Long = 0

    constructor()

    protected constructor(`in`: Parcel) {
        gid = `in`.readLong()
        token = `in`.readString()
        title = `in`.readString()
        titleJpn = `in`.readString()
        thumb = `in`.readString()
        category = `in`.readInt()
        posted = `in`.readString()
        uploader = `in`.readString()
        rating = `in`.readFloat()
        rated = `in`.readByte().toInt() != 0
        simpleLanguage = `in`.readString()
        simpleTags = `in`.createStringArray()
        thumbWidth = `in`.readInt()
        thumbHeight = `in`.readInt()
        spanSize = `in`.readInt()
        spanIndex = `in`.readInt()
        spanGroupIndex = `in`.readInt()
        favoriteSlot = `in`.readInt()
        favoriteName = `in`.readString()
        @Suppress("UNCHECKED_CAST")
        tgList = `in`.readArrayList(String::class.java.classLoader) as? ArrayList<String>
        serverProfileId = `in`.readLong()
        progress = `in`.readInt()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(gid)
        dest.writeString(token)
        dest.writeString(title)
        dest.writeString(titleJpn)
        dest.writeString(thumb)
        dest.writeInt(category)
        dest.writeString(posted)
        dest.writeString(uploader)
        dest.writeFloat(rating)
        dest.writeByte(if (rated) 1.toByte() else 0.toByte())
        dest.writeString(simpleLanguage)
        dest.writeStringArray(simpleTags)
        dest.writeInt(thumbWidth)
        dest.writeInt(thumbHeight)
        dest.writeInt(spanSize)
        dest.writeInt(spanIndex)
        dest.writeInt(spanGroupIndex)
        dest.writeInt(favoriteSlot)
        dest.writeString(favoriteName)
        dest.writeList(tgList)
        dest.writeLong(serverProfileId)
        dest.writeInt(progress)
    }

    override fun describeContents(): Int = 0

    fun generateSLang() {
        if (simpleTags != null) {
            generateSLangFromTags()
        }
        if (simpleLanguage == null && title != null) {
            generateSLangFromTitle()
        }
    }

    private fun generateSLangFromTags() {
        val tags = simpleTags ?: return
        for (tag in tags) {
            for (i in S_LANGS.indices) {
                if (S_LANG_TAGS[i] == tag) {
                    simpleLanguage = S_LANGS[i]
                    return
                }
            }
        }
    }

    private fun generateSLangFromTitle() {
        val t = title ?: return
        for (i in S_LANGS.indices) {
            if (S_LANG_PATTERNS[i].matcher(t).find()) {
                simpleLanguage = S_LANGS[i]
                return
            }
        }
        simpleLanguage = null
    }

    fun toCSV(): String {
        return gid.toString() + "," +
            token + "," +
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

    open fun toJson(): JSONObject {
        try {
            val jsonObject = JSONObject()
            jsonObject.put("gid", gid)
            jsonObject.put("token", token)
            jsonObject.put("title", title)
            jsonObject.put("titleJpn", titleJpn)
            jsonObject.put("thumb", thumb)
            jsonObject.put("category", category)
            jsonObject.put("posted", posted)
            jsonObject.put("uploader", uploader)
            jsonObject.put("rating", rating.toDouble())
            jsonObject.put("rated", rated)
            jsonObject.put("simpleLanguage", simpleLanguage)
            if (simpleTags != null) {
                val tagsArr = JSONArray()
                for (tag in simpleTags!!) {
                    tagsArr.put(tag)
                }
                jsonObject.put("simpleTags", tagsArr)
            }
            jsonObject.put("thumbHeight", thumbHeight)
            jsonObject.put("thumbWidth", thumbWidth)
            jsonObject.put("spanSize", spanSize)
            jsonObject.put("spanIndex", spanIndex)
            jsonObject.put("spanGroupIndex", spanGroupIndex)
            jsonObject.put("favoriteSlot", favoriteSlot)
            jsonObject.put("favoriteName", favoriteName)
            if (tgList != null) {
                val tgArr = JSONArray()
                for (t in tgList!!) {
                    tgArr.put(t)
                }
                jsonObject.put("tgList", tgArr)
            }
            jsonObject.put("pages", pages)
            jsonObject.put("serverProfileId", serverProfileId)
            return jsonObject
        } catch (e: JSONException) {
            return JSONObject()
        }
    }

    fun getDownloadInfo(info: DownloadInfo?): DownloadInfo {
        val i = DownloadInfo()
        i.gid = gid
        i.token = token
        i.title = title
        i.titleJpn = titleJpn
        i.thumb = thumb
        i.category = category
        i.posted = posted
        i.uploader = uploader
        i.rating = rating
        i.rated = rated
        i.simpleLanguage = simpleLanguage
        i.simpleTags = simpleTags
        i.thumbWidth = thumbWidth
        i.thumbHeight = thumbHeight
        i.spanSize = spanSize
        i.spanIndex = spanIndex
        i.spanGroupIndex = spanGroupIndex
        i.favoriteSlot = favoriteSlot
        i.favoriteName = favoriteName
        i.tgList = tgList
        i.progress = progress
        if (info != null) {
            i.state = info.state
            i.legacy = info.legacy
            i.time = info.time
            i.label = info.label
        }
        return i
    }

    companion object {
        /** ISO 639-1 */
        const val S_LANG_JA: String = "JA"
        const val S_LANG_EN: String = "EN"
        const val S_LANG_ZH: String = "ZH"
        const val S_LANG_NL: String = "NL"
        const val S_LANG_FR: String = "FR"
        const val S_LANG_DE: String = "DE"
        const val S_LANG_HU: String = "HU"
        const val S_LANG_IT: String = "IT"
        const val S_LANG_KO: String = "KO"
        const val S_LANG_PL: String = "PL"
        const val S_LANG_PT: String = "PT"
        const val S_LANG_RU: String = "RU"
        const val S_LANG_ES: String = "ES"
        const val S_LANG_TH: String = "TH"
        const val S_LANG_VI: String = "VI"

        @JvmField
        val S_LANGS: Array<String> = arrayOf(
            S_LANG_EN,
            S_LANG_ZH,
            S_LANG_ES,
            S_LANG_KO,
            S_LANG_RU,
            S_LANG_FR,
            S_LANG_PT,
            S_LANG_TH,
            S_LANG_DE,
            S_LANG_IT,
            S_LANG_VI,
            S_LANG_PL,
            S_LANG_HU,
            S_LANG_NL,
        )

        @JvmField
        val S_LANG_PATTERNS: Array<Pattern> = arrayOf(
            Pattern.compile("[(\\[]eng(?:lish)?[)\\]]|英訳", Pattern.CASE_INSENSITIVE),
            // [(（\[]ch(?:inese)?[)）\]]|[汉漢]化|中[国國][语語]|中文|中国翻訳
            Pattern.compile("[(\uFF08\\[]ch(?:inese)?[)\uFF09\\]]|[汉漢]化|中[国國][语語]|中文|中国翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]spanish[)\\]]|[(\\[]Español[)\\]]|スペイン翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]korean?[)\\]]|韓国翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]rus(?:sian)?[)\\]]|ロシア翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]fr(?:ench)?[)\\]]|フランス翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]portuguese|ポルトガル翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]thai(?: ภาษาไทย)?[)\\]]|แปลไทย|タイ翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]german[)\\]]|ドイツ翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]italiano?[)\\]]|イタリア翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]vietnamese(?: Tiếng Việt)?[)\\]]|ベトナム翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]polish[)\\]]|ポーランド翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]hun(?:garian)?[)\\]]|ハンガリー翻訳", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[(\\[]dutch[)\\]]|オランダ翻訳", Pattern.CASE_INSENSITIVE),
        )

        @JvmField
        val S_LANG_TAGS: Array<String> = arrayOf(
            "language:english",
            "language:chinese",
            "language:spanish",
            "language:korean",
            "language:russian",
            "language:french",
            "language:portuguese",
            "language:thai",
            "language:german",
            "language:italian",
            "language:vietnamese",
            "language:polish",
            "language:hungarian",
            "language:dutch",
        )

        @JvmField
        val CREATOR: Parcelable.Creator<GalleryInfo> = object : Parcelable.Creator<GalleryInfo> {
            override fun createFromParcel(source: Parcel): GalleryInfo = GalleryInfo(source)
            override fun newArray(size: Int): Array<GalleryInfo?> = arrayOfNulls(size)
        }

        @JvmStatic
        fun fromCSV(csv: String): GalleryInfo? {
            val values = csv.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (values.size < 20) {
                return null
            }
            val gi = GalleryInfo()
            try {
                gi.gid = values[0].toLong()
                gi.token = values[1]
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

        @JvmStatic
        fun galleryInfoFromJson(obj: JSONObject): GalleryInfo {
            val galleryInfo = GalleryInfo()
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
                } catch (_: Exception) {
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
                } catch (_: Exception) {
                }
            }
            galleryInfo.thumb = obj.optString("thumb", null)
            galleryInfo.thumbHeight = obj.optInt("thumbHeight", 0)
            galleryInfo.thumbWidth = obj.optInt("thumbWidth", 0)
            galleryInfo.title = obj.optString("title", null)
            galleryInfo.titleJpn = obj.optString("titleJpn", null)
            galleryInfo.token = obj.optString("token", null)
            galleryInfo.uploader = obj.optString("uploader", null)
            galleryInfo.serverProfileId = obj.optLong("serverProfileId", 0)
            return galleryInfo
        }
    }
}
