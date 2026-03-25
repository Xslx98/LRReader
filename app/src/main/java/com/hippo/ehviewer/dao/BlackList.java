package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity mapped to table "Black_List".
 */
@Entity(tableName = "Black_List",
    indices = {@Index("BADGAYNAME")})
public class BlackList {

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "_id")
	public Long id;

	@androidx.annotation.Nullable
	@ColumnInfo(name = "BADGAYNAME")
	public String badgayname;
	@androidx.annotation.Nullable @ColumnInfo(name = "REASON") public String reason;
	@androidx.annotation.Nullable @ColumnInfo(name = "ANGRYWITH") public String angrywith;
	@androidx.annotation.Nullable @ColumnInfo(name = "ADD_TIME") public String add_time;
	@androidx.annotation.Nullable @ColumnInfo(name = "MODE") public Integer mode;

	public BlackList() {
	}

	@Ignore
	public BlackList(Long id) {
		this.id = id;
	}

	@Ignore
	public BlackList(Long id, String badgayname, String reason, String angrywith, String add_time, Integer mode) {
		this.id = id;
		this.badgayname = badgayname;
		this.reason = reason;
		this.angrywith = angrywith;
		this.add_time = add_time;
		this.mode = mode;
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getBadgayname() { return badgayname; }
	public void setBadgayname(String badgayname) { this.badgayname = badgayname; }
	public String getReason() { return reason; }
	public void setReason(String reason) { this.reason = reason; }
	public String getAngrywith() { return angrywith; }
	public void setAngrywith(String angrywith) { this.angrywith = angrywith; }
	public String getAdd_time() { return add_time; }
	public void setAdd_time(String add_time) { this.add_time = add_time; }
	public Integer getMode() { return mode; }
	public void setMode(Integer mode) { this.mode = mode; }

	@Override
	public String toString() { return badgayname; }

}
