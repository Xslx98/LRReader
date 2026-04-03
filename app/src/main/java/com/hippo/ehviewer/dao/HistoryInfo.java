package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;

import android.os.Parcel;
import com.hippo.ehviewer.client.data.GalleryInfo;

/**
 * Entity mapped to table "HISTORY".
 * Primary key is GID (inherited from GalleryInfo).
 */
@Entity(tableName = "HISTORY", primaryKeys = {"GID"}, indices = {
    @Index("SERVER_PROFILE_ID"),
    @Index("TIME")
})
public class HistoryInfo extends GalleryInfo {

	@ColumnInfo(name = "MODE") public int mode;
	@ColumnInfo(name = "TIME") public long time;

	public static final Creator<HistoryInfo> CREATOR = new Creator<HistoryInfo>() {
		@Override
		public HistoryInfo createFromParcel(Parcel source) {
			return new HistoryInfo(source);
		}

		@Override
		public HistoryInfo[] newArray(int size) {
			return new HistoryInfo[size];
		}
	};

	public HistoryInfo() {
	}

	@Ignore
	public HistoryInfo(long gid) {
		this.gid = gid;
	}

	@Ignore
	public HistoryInfo(long gid, String token, String title, String titleJpn, String thumb, int category, String posted,
			String uploader, float rating, String simpleLanguage, int mode, long time) {
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
		this.mode = mode;
		this.time = time;
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
	public int getMode() { return mode; }
	public void setMode(int mode) { this.mode = mode; }
	public long getTime() { return time; }
	public void setTime(long time) { this.time = time; }

	@Override
	public int describeContents() { return 0; }

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		super.writeToParcel(dest, flags);
		dest.writeInt(this.mode);
		dest.writeLong(this.time);
	}

	@Ignore
	protected HistoryInfo(Parcel in) {
		super(in);
		this.mode = in.readInt();
		this.time = in.readLong();
	}

	@Ignore
	public HistoryInfo(GalleryInfo galleryInfo) {
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

}
