package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.hippo.util.HashCodeUtils;
import com.hippo.yorozuya.ObjectUtils;

/**
 * Entity mapped to table "FILTER".
 */
@Entity(tableName = "FILTER")
public class Filter {

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "_id")
	private Long id;
	@ColumnInfo(name = "MODE") public int mode;
	@androidx.annotation.Nullable @ColumnInfo(name = "TEXT") public String text;
	@androidx.annotation.Nullable @ColumnInfo(name = "ENABLE") public Boolean enable;

	public Filter() {
	}

	@Ignore
	public Filter(Long id) {
		this.id = id;
	}

	@Ignore
	public Filter(Long id, int mode, String text, Boolean enable) {
		this.id = id;
		this.mode = mode;
		this.text = text;
		this.enable = enable;
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public int getMode() { return mode; }
	public void setMode(int mode) { this.mode = mode; }
	public String getText() { return text; }
	public void setText(String text) { this.text = text; }
	public Boolean getEnable() { return enable; }
	public void setEnable(Boolean enable) { this.enable = enable; }

	@Override
	public int hashCode() {
		return HashCodeUtils.hashCode(mode, text);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Filter)) {
			return false;
		}
		Filter filter = (Filter) o;
		return filter.mode == mode && ObjectUtils.equal(filter.text, text);
	}

}
