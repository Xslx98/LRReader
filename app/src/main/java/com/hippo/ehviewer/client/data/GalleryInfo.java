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

package com.hippo.ehviewer.client.data;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import androidx.room.ColumnInfo;
import androidx.room.Ignore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.hippo.ehviewer.dao.DownloadInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

public class GalleryInfo implements Parcelable {

    /**
     * ISO 639-1
     */
    @SuppressWarnings("unused")
    public static final String S_LANG_JA = "JA";
    public static final String S_LANG_EN = "EN";
    public static final String S_LANG_ZH = "ZH";
    public static final String S_LANG_NL = "NL";
    public static final String S_LANG_FR = "FR";
    public static final String S_LANG_DE = "DE";
    public static final String S_LANG_HU = "HU";
    public static final String S_LANG_IT = "IT";
    public static final String S_LANG_KO = "KO";
    public static final String S_LANG_PL = "PL";
    public static final String S_LANG_PT = "PT";
    public static final String S_LANG_RU = "RU";
    public static final String S_LANG_ES = "ES";
    public static final String S_LANG_TH = "TH";
    public static final String S_LANG_VI = "VI";

    public static final String[] S_LANGS = {
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
    };

    public static final Pattern[] S_LANG_PATTERNS = {
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
    };

    public static final String[] S_LANG_TAGS = {
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
    };

    @ColumnInfo(name = "GID")
    public long gid;
    @Nullable
    @ColumnInfo(name = "TOKEN")
    public String token;
    @Nullable
    @ColumnInfo(name = "TITLE")
    public String title;
    @Nullable
    @ColumnInfo(name = "TITLE_JPN")
    public String titleJpn;
    @Nullable
    @ColumnInfo(name = "THUMB")
    public String thumb;
    @ColumnInfo(name = "CATEGORY")
    public int category;
    @Nullable
    @ColumnInfo(name = "POSTED")
    public String posted;
    @Nullable
    @ColumnInfo(name = "UPLOADER")
    public String uploader;
    @ColumnInfo(name = "RATING")
    public float rating;
    @Ignore
    public boolean rated;
    @Ignore
    @Nullable
    public String[] simpleTags;
    @Ignore
    public int pages;

    @Ignore
    public int thumbWidth;
    @Ignore
    public int thumbHeight;

    @Ignore
    public int spanSize;
    @Ignore
    public int spanIndex;
    @Ignore
    public int spanGroupIndex;
    @Ignore
    @Nullable
    public ArrayList<String> tgList;

    /**
     * language from title
     */
    @Nullable
    @ColumnInfo(name = "SIMPLE_LANGUAGE")
    public String simpleLanguage;

    @Ignore
    public int favoriteSlot = -2;
    @Ignore
    public String favoriteName;

    /**
     * ID of the ServerProfile this entry belongs to.
     * Used to filter history/downloads by server.
     */
    @ColumnInfo(name = "SERVER_PROFILE_ID", defaultValue = "0")
    public long serverProfileId;

    public String toCSV() {
        return gid + "," +
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
                Arrays.toString(simpleTags) + "," +
                thumbWidth + "," +
                thumbHeight + "," +
                spanSize + "," +
                spanIndex + "," +
                spanGroupIndex + "," +
                favoriteSlot + "," +
                favoriteName + "," +
                pages + "\n";
    }

    public static GalleryInfo fromCSV(String csv) {
        String[] values = csv.split(",");
        if (values.length < 20) {
            return null;
        }
        GalleryInfo gi = new GalleryInfo();
        try {
            gi.gid = Long.parseLong(values[0]);
            gi.token = values[1];
            gi.title = values[2];
            gi.titleJpn = values[3];
            gi.thumb = values[4];
            gi.category = Integer.parseInt(values[5]);
            gi.posted = values[6];
            gi.uploader = values[7];
            gi.rating = Float.parseFloat(values[8]);
            gi.rated = Boolean.parseBoolean(values[9]);
            gi.simpleLanguage = values[10];
            gi.simpleTags = values[11].substring(1, values[11].length() - 1).split(", ");
            gi.thumbWidth = Integer.parseInt(values[12]);
            gi.thumbHeight = Integer.parseInt(values[13]);
            gi.spanSize = Integer.parseInt(values[14]);
            gi.spanIndex = Integer.parseInt(values[15]);
            gi.spanGroupIndex = Integer.parseInt(values[16]);
            gi.favoriteSlot = Integer.parseInt(values[17]);
            gi.favoriteName = values[18];
            gi.pages = Integer.parseInt(values[19].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return gi;
    }

    public final void generateSLang() {
        if (simpleTags != null) {
            generateSLangFromTags();
        }
        if (simpleLanguage == null && title != null) {
            generateSLangFromTitle();
        }
    }

    private void generateSLangFromTags() {
        if(simpleTags==null){
            return;
        }
        for (String tag : simpleTags) {
            for (int i = 0; i < S_LANGS.length; i++) {
                if (S_LANG_TAGS[i].equals(tag)) {
                    simpleLanguage = S_LANGS[i];
                    return;
                }
            }
        }
    }

    private void generateSLangFromTitle() {
        for (int i = 0; i < S_LANGS.length; i++) {
            if (S_LANG_PATTERNS[i].matcher(title).find()) {
                simpleLanguage = S_LANGS[i];
                return;
            }
        }
        simpleLanguage = null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(this.gid);
        dest.writeString(this.token);
        dest.writeString(this.title);
        dest.writeString(this.titleJpn);
        dest.writeString(this.thumb);
        dest.writeInt(this.category);
        dest.writeString(this.posted);
        dest.writeString(this.uploader);
        dest.writeFloat(this.rating);
        dest.writeByte(this.rated ? (byte) 1 : (byte) 0);
        dest.writeString(this.simpleLanguage);
        dest.writeStringArray(this.simpleTags);
        dest.writeInt(this.thumbWidth);
        dest.writeInt(this.thumbHeight);
        dest.writeInt(this.spanSize);
        dest.writeInt(this.spanIndex);
        dest.writeInt(this.spanGroupIndex);
        dest.writeInt(this.favoriteSlot);
        dest.writeString(this.favoriteName);
        dest.writeList(this.tgList);
        dest.writeLong(this.serverProfileId);
    }

    public GalleryInfo() {
    }

    protected GalleryInfo(Parcel in) {
        this.gid = in.readLong();
        this.token = in.readString();
        this.title = in.readString();
        this.titleJpn = in.readString();
        this.thumb = in.readString();
        this.category = in.readInt();
        this.posted = in.readString();
        this.uploader = in.readString();
        this.rating = in.readFloat();
        this.rated = in.readByte() != 0;
        this.simpleLanguage = in.readString();
        this.simpleTags = in.createStringArray();
        this.thumbWidth = in.readInt();
        this.thumbHeight = in.readInt();
        this.spanSize = in.readInt();
        this.spanIndex = in.readInt();
        this.spanGroupIndex = in.readInt();
        this.favoriteSlot = in.readInt();
        this.favoriteName = in.readString();
        this.tgList = in.readArrayList(String.class.getClassLoader());
        this.serverProfileId = in.readLong();
    }

    public static final Creator<GalleryInfo> CREATOR = new Creator<>() {

        @Override
        public GalleryInfo createFromParcel(Parcel source) {
            return new GalleryInfo(source);
        }

        @Override
        public GalleryInfo[] newArray(int size) {
            return new GalleryInfo[size];
        }
    };

    public DownloadInfo getDownloadInfo(@Nullable DownloadInfo info) {
        DownloadInfo i = new DownloadInfo();
        i.gid = gid;
        i.token = token;
        i.title = title;
        i.titleJpn = titleJpn;
        i.thumb = thumb;
        i.category = category;
        i.posted = posted;
        i.uploader = uploader;
        i.rating = rating;
        i.rated = rated;
        i.simpleLanguage = simpleLanguage;
        i.simpleTags = simpleTags;
        i.thumbWidth = thumbWidth;
        i.thumbHeight = thumbHeight;
        i.spanSize = spanSize;
        i.spanIndex = spanIndex;
        i.spanGroupIndex = spanGroupIndex;
        i.favoriteSlot = favoriteSlot;
        i.favoriteName = favoriteName;
        i.tgList = tgList;
        if (info != null) {
            i.state = info.state;
            i.legacy = info.legacy;
            i.time = info.time;
            i.label = info.label;
        }

        return i;
    }

    public JSONObject toJson() {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("gid", gid);
            jsonObject.put("token", token);
            jsonObject.put("title", title);
            jsonObject.put("titleJpn", titleJpn);
            jsonObject.put("thumb", thumb);
            jsonObject.put("category", category);
            jsonObject.put("posted", posted);
            jsonObject.put("uploader", uploader);
            jsonObject.put("rating", rating);
            jsonObject.put("rated", rated);
            jsonObject.put("simpleLanguage", simpleLanguage);
            if (simpleTags != null) {
                JSONArray tagsArr = new JSONArray();
                for (String tag : simpleTags) {
                    tagsArr.put(tag);
                }
                jsonObject.put("simpleTags", tagsArr);
            }
            jsonObject.put("thumbHeight", thumbHeight);
            jsonObject.put("thumbWidth", thumbWidth);
            jsonObject.put("spanSize", spanSize);
            jsonObject.put("spanIndex", spanIndex);
            jsonObject.put("spanGroupIndex", spanGroupIndex);
            jsonObject.put("favoriteSlot", favoriteSlot);
            jsonObject.put("favoriteName", favoriteName);
            if (tgList != null) {
                JSONArray tgArr = new JSONArray();
                for (String t : tgList) {
                    tgArr.put(t);
                }
                jsonObject.put("tgList", tgArr);
            }
            jsonObject.put("pages", pages);
            jsonObject.put("serverProfileId", serverProfileId);
            return jsonObject;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static GalleryInfo galleryInfoFromJson(JSONObject object) {
        GalleryInfo galleryInfo = new GalleryInfo();
        galleryInfo.posted = object.optString("posted", null);
        galleryInfo.category = object.optInt("category", 0);
        galleryInfo.favoriteName = object.optString("favoriteName", null);
        galleryInfo.favoriteSlot = object.optInt("favoriteSlot", 0);
        galleryInfo.gid = object.optLong("gid", 0);
        galleryInfo.pages = object.optInt("pages", 0);
        galleryInfo.rated = object.optBoolean("rated", false);
        galleryInfo.rating = (float) object.optDouble("rating", 0);
        galleryInfo.simpleLanguage = object.optString("simpleLanguage", null);
        JSONArray simpleTagsArr = object.optJSONArray("simpleTags");
        if (simpleTagsArr != null) {
            try {
                String[] tags = new String[simpleTagsArr.length()];
                for (int i = 0; i < simpleTagsArr.length(); i++) {
                    tags[i] = simpleTagsArr.getString(i);
                }
                galleryInfo.simpleTags = tags;
            } catch (Exception ignore) {
            }
        }
        galleryInfo.spanGroupIndex = object.optInt("spanGroupIndex", 0);
        galleryInfo.spanIndex = object.optInt("spanIndex", 0);
        galleryInfo.spanSize = object.optInt("spanSize", 0);
        JSONArray tgArray = object.optJSONArray("tgList");
        if (tgArray != null) {
            try {
                ArrayList<String> list = new ArrayList<>();
                for (int i = 0; i < tgArray.length(); i++) {
                    list.add(tgArray.getString(i));
                }
                galleryInfo.tgList = list;
            } catch (Exception ignore) {
            }
        }

        galleryInfo.thumb = object.optString("thumb", null);
        galleryInfo.thumbHeight = object.optInt("thumbHeight", 0);
        galleryInfo.thumbWidth = object.optInt("thumbWidth", 0);
        galleryInfo.title = object.optString("title", null);
        galleryInfo.titleJpn = object.optString("titleJpn", null);
        galleryInfo.token = object.optString("token", null);
        galleryInfo.uploader = object.optString("uploader", null);
        galleryInfo.serverProfileId = object.optLong("serverProfileId", 0);
        return galleryInfo;
    }
}
