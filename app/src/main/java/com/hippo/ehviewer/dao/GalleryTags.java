package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

import com.google.gson.Gson;

/**
 * Entity mapped to table "Gallery_Tags".
 */
@Entity(tableName = "Gallery_Tags")
@TypeConverters(DateConverter.class)
public class GalleryTags {

	@PrimaryKey
	@ColumnInfo(name = "GID")
	public long gid;
	@androidx.annotation.Nullable @ColumnInfo(name = "ROWS") public String rows;
	@androidx.annotation.Nullable @ColumnInfo(name = "ARTIST") public String artist;
	@androidx.annotation.Nullable @ColumnInfo(name = "COSPLAYER") public String cosplayer;
	@androidx.annotation.Nullable @ColumnInfo(name = "CHARACTER") public String character;
	@androidx.annotation.Nullable @ColumnInfo(name = "FEMALE") public String female;
	@androidx.annotation.Nullable @ColumnInfo(name = "GROUP") public String group;
	@androidx.annotation.Nullable @ColumnInfo(name = "LANGUAGE") public String language;
	@androidx.annotation.Nullable @ColumnInfo(name = "MALE") public String male;
	@androidx.annotation.Nullable @ColumnInfo(name = "MISC") public String misc;
	@androidx.annotation.Nullable @ColumnInfo(name = "MIXED") public String mixed;
	@androidx.annotation.Nullable @ColumnInfo(name = "OTHER") public String other;
	@androidx.annotation.Nullable @ColumnInfo(name = "PARODY") public String parody;
	@androidx.annotation.Nullable @ColumnInfo(name = "RECLASS") public String reclass;
	@androidx.annotation.Nullable @ColumnInfo(name = "CREATE_TIME") public java.util.Date create_time;
	@androidx.annotation.Nullable @ColumnInfo(name = "UPDATE_TIME") public java.util.Date update_time;

	public GalleryTags() {
	}

	@Ignore
	public GalleryTags(long gid) {
		this.gid = gid;
	}

	@Ignore
	public GalleryTags(long gid, String rows, String artist, String cosplayer, String character, String female,
			String group, String language, String male, String misc, String mixed, String other, String parody,
			String reclass, java.util.Date create_time, java.util.Date update_time) {
		this.gid = gid;
		this.rows = rows;
		this.artist = artist;
		this.cosplayer = cosplayer;
		this.character = character;
		this.female = female;
		this.group = group;
		this.language = language;
		this.male = male;
		this.misc = misc;
		this.mixed = mixed;
		this.other = other;
		this.parody = parody;
		this.reclass = reclass;
		this.create_time = create_time;
		this.update_time = update_time;
	}

	public long getGid() { return gid; }
	public void setGid(long gid) { this.gid = gid; }
	public String getRows() { return rows; }
	public void setRows(String rows) { this.rows = rows; }
	public String getArtist() { return artist; }
	public void setArtist(String artist) { this.artist = artist; }
	public String getCosplayer() { return cosplayer; }
	public void setCosplayer(String cosplayer) { this.cosplayer = cosplayer; }
	public String getCharacter() { return character; }
	public void setCharacter(String character) { this.character = character; }
	public String getFemale() { return female; }
	public void setFemale(String female) { this.female = female; }
	public String getGroup() { return group; }
	public void setGroup(String group) { this.group = group; }
	public String getLanguage() { return language; }
	public void setLanguage(String language) { this.language = language; }
	public String getMale() { return male; }
	public void setMale(String male) { this.male = male; }
	public String getMisc() { return misc; }
	public void setMisc(String misc) { this.misc = misc; }
	public String getMixed() { return mixed; }
	public void setMixed(String mixed) { this.mixed = mixed; }
	public String getOther() { return other; }
	public void setOther(String other) { this.other = other; }
	public String getParody() { return parody; }
	public void setParody(String parody) { this.parody = parody; }
	public String getReclass() { return reclass; }
	public void setReclass(String reclass) { this.reclass = reclass; }
	public java.util.Date getCreate_time() { return create_time; }
	public void setCreate_time(java.util.Date create_time) { this.create_time = create_time; }
	public java.util.Date getUpdate_time() { return update_time; }
	public void setUpdate_time(java.util.Date update_time) { this.update_time = update_time; }

	@Override
	public String toString() {
		Gson gson = new Gson();
		return gson.toJson(this);
	}

}
