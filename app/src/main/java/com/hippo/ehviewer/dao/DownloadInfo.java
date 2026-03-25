package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;

import android.os.Parcel;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.google.gson.JsonObject;
import java.util.ArrayList;

/**
 * Entity mapped to table "DOWNLOADS".
 * Primary key is GID (inherited from GalleryInfo).
 */
@Entity(tableName = "DOWNLOADS", primaryKeys = {"GID"})
public class DownloadInfo extends GalleryInfo {

	@ColumnInfo(name = "STATE") public int state;
	@ColumnInfo(name = "LEGACY") public int legacy;
	@ColumnInfo(name = "TIME") public long time;
	@androidx.annotation.Nullable @ColumnInfo(name = "LABEL") public String label;
	@androidx.annotation.Nullable @ColumnInfo(name = "ARCHIVE_URI") public String archiveUri;

	public static final Creator<DownloadInfo> CREATOR = new Creator<DownloadInfo>() {
		@Override
		public DownloadInfo createFromParcel(Parcel source) {
			return new DownloadInfo(source);
		}

		@Override
		public DownloadInfo[] newArray(int size) {
			return new DownloadInfo[size];
		}
	};
	public static final int STATE_INVALID = -1;
	public static final int STATE_NONE = 0;
	public static final int STATE_WAIT = 1;
	public static final int STATE_DOWNLOAD = 2;
	public static final int STATE_FINISH = 3;
	public static final int STATE_FAILED = 4;
	public static final int STATE_UPDATE = 5;
	public static final int GOTO_NEW = 6;
	@Ignore public long speed;
	@Ignore public long remaining;
	@Ignore public int finished;
	@Ignore public int downloaded;
	@Ignore public int total;
	@Ignore public long fileSize = -1;


	public DownloadInfo() {
	}

	@Ignore
	public DownloadInfo(long gid) {
		this.gid = gid;
	}

	@Ignore
	public DownloadInfo(long gid, String token, String title, String titleJpn, String thumb, int category,
			String posted, String uploader, float rating, String simpleLanguage, int state, int legacy, long time,
			String label, String archiveUri) {
		this.gid = gid;
		this.token = token;
		this.title = title;
		this.titleJpn = titleJpn;
		this.thumb = thumb;
		this.category = category;
		this.posted = posted;
		this.uploader = uploader;
		this.rating = rating;
		this.simpleLanguage = simpleLanguage;
		this.state = state;
		this.legacy = legacy;
		this.time = time;
		this.label = label;
		this.archiveUri = archiveUri;
	}

	public long getGid() { return gid; }
	public void setGid(long gid) { this.gid = gid; }
	public String getToken() { return token; }
	public void setToken(String token) { this.token = token; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getTitleJpn() { return titleJpn; }
	public void setTitleJpn(String titleJpn) { this.titleJpn = titleJpn; }
	public String getThumb() { return thumb; }
	public void setThumb(String thumb) { this.thumb = thumb; }
	public int getCategory() { return category; }
	public void setCategory(int category) { this.category = category; }
	public String getPosted() { return posted; }
	public void setPosted(String posted) { this.posted = posted; }
	public String getUploader() { return uploader; }
	public void setUploader(String uploader) { this.uploader = uploader; }
	public float getRating() { return rating; }
	public void setRating(float rating) { this.rating = rating; }
	public String getSimpleLanguage() { return simpleLanguage; }
	public void setSimpleLanguage(String simpleLanguage) { this.simpleLanguage = simpleLanguage; }
	public int getState() { return state; }
	public void setState(int state) { this.state = state; }
	public int getLegacy() { return legacy; }
	public void setLegacy(int legacy) { this.legacy = legacy; }
	public long getTime() { return time; }
	public void setTime(long time) { this.time = time; }
	public String getLabel() { return label; }
	public void setLabel(String label) { this.label = label; }
	public String getArchiveUri() { return archiveUri; }
	public void setArchiveUri(String archiveUri) { this.archiveUri = archiveUri; }

	@Override
	public int describeContents() { return 0; }

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		super.writeToParcel(dest, flags);
		dest.writeInt(this.state);
		dest.writeInt(this.legacy);
		dest.writeLong(this.time);
		dest.writeString(this.label);
		dest.writeString(this.archiveUri);
	}

	@Ignore
	protected DownloadInfo(Parcel in) {
		super(in);
		this.state = in.readInt();
		this.legacy = in.readInt();
		this.time = in.readLong();
		this.label = in.readString();
		this.archiveUri = in.readString();
	}

	@Ignore
	public DownloadInfo(GalleryInfo galleryInfo) {
		this.gid = galleryInfo.gid;
		this.token = galleryInfo.token;
		this.title = galleryInfo.title;
		this.titleJpn = galleryInfo.titleJpn;
		this.thumb = galleryInfo.thumb;
		this.category = galleryInfo.category;
		this.posted = galleryInfo.posted;
		this.uploader = galleryInfo.uploader;
		this.rating = galleryInfo.rating;
		this.simpleTags = galleryInfo.simpleTags;
		this.simpleLanguage = galleryInfo.simpleLanguage;
		this.serverProfileId = galleryInfo.serverProfileId;
	}

	public void updateInfo(GalleryInfo galleryInfo) {
		this.token = galleryInfo.token;
		this.title = galleryInfo.title;
		this.titleJpn = galleryInfo.titleJpn;
		this.thumb = galleryInfo.thumb;
		this.category = galleryInfo.category;
		this.posted = galleryInfo.posted;
		this.uploader = galleryInfo.uploader;
		this.rating = galleryInfo.rating;
		this.simpleTags = galleryInfo.simpleTags;
		this.simpleLanguage = galleryInfo.simpleLanguage;
	}

	public JsonObject toJson() {
		JsonObject jsonObject = super.toJson();
		jsonObject.addProperty("finished", finished);
		jsonObject.addProperty("legacy", legacy);
		jsonObject.addProperty("label", label);
		jsonObject.addProperty("downloaded", downloaded);
		jsonObject.addProperty("remaining", remaining);
		jsonObject.addProperty("speed", speed);
		jsonObject.addProperty("state", state);
		jsonObject.addProperty("time", time);
		jsonObject.addProperty("total", total);
		jsonObject.addProperty("archiveUri", archiveUri);
		return jsonObject;
	}

	public static DownloadInfo downloadInfoFromJson(JsonObject object) throws ClassCastException {
		DownloadInfo downloadInfo = (DownloadInfo) GalleryInfo.galleryInfoFromJson(object);
		downloadInfo.finished = object.has("finished") ? object.get("finished").getAsInt() : 0;
		downloadInfo.legacy = object.has("legacy") ? object.get("legacy").getAsInt() : 0;
		downloadInfo.label = object.has("label") ? object.get("label").getAsString() : null;
		downloadInfo.downloaded = object.has("downloaded") ? object.get("downloaded").getAsInt() : 0;
		downloadInfo.remaining = object.has("remaining") ? object.get("remaining").getAsLong() : 0;
		downloadInfo.speed = object.has("speed") ? object.get("speed").getAsLong() : 0;
		downloadInfo.state = object.has("state") ? object.get("state").getAsInt() : 0;
		downloadInfo.time = object.has("time") ? object.get("time").getAsLong() : 0;
		downloadInfo.total = object.has("total") ? object.get("total").getAsInt() : 0;
		downloadInfo.archiveUri = object.has("archiveUri") ? object.get("archiveUri").getAsString() : null;
		return downloadInfo;
	}

}
