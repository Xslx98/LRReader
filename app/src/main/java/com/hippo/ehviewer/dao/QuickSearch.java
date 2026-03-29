package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Entity mapped to table "QUICK_SEARCH".
 */
@Entity(tableName = "QUICK_SEARCH")
public class QuickSearch {

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "_id")
	public Long id;
	@androidx.annotation.Nullable @ColumnInfo(name = "NAME") public String name;
	@ColumnInfo(name = "MODE") public int mode;
	@ColumnInfo(name = "CATEGORY") public int category;
	@androidx.annotation.Nullable @ColumnInfo(name = "KEYWORD") public String keyword;
	@ColumnInfo(name = "ADVANCE_SEARCH") public int advanceSearch;
	@ColumnInfo(name = "MIN_RATING") public int minRating;
	@ColumnInfo(name = "PAGE_FROM") public int pageFrom;
	@ColumnInfo(name = "PAGE_TO") public int pageTo;
	@ColumnInfo(name = "TIME") public long time;

	public QuickSearch() {
	}

	@Ignore
	public QuickSearch(Long id) {
		this.id = id;
	}

	@Ignore
	public QuickSearch(Long id, String name, int mode, int category, String keyword, int advanceSearch, int minRating,
			int pageFrom, int pageTo, long time) {
		this.id = id;
		this.name = name;
		this.mode = mode;
		this.category = category;
		this.keyword = keyword;
		this.advanceSearch = advanceSearch;
		this.minRating = minRating;
		this.pageFrom = pageFrom;
		this.pageTo = pageTo;
		this.time = time;
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public int getMode() { return mode; }
	public void setMode(int mode) { this.mode = mode; }
	public int getCategory() { return category; }
	public void setCategory(int category) { this.category = category; }
	public String getKeyword() { return keyword; }
	public void setKeyword(String keyword) { this.keyword = keyword; }
	public int getAdvanceSearch() { return advanceSearch; }
	public void setAdvanceSearch(int advanceSearch) { this.advanceSearch = advanceSearch; }
	public int getMinRating() { return minRating; }
	public void setMinRating(int minRating) { this.minRating = minRating; }
	public int getPageFrom() { return pageFrom; }
	public void setPageFrom(int pageFrom) { this.pageFrom = pageFrom; }
	public int getPageTo() { return pageTo; }
	public void setPageTo(int pageTo) { this.pageTo = pageTo; }
	public long getTime() { return time; }
	public void setTime(long time) { this.time = time; }

	@Override
	public String toString() { return name; }

	public JSONObject toJson() {
		try {
			JSONObject object = new JSONObject();
			object.put("name", name);
			object.put("mode", mode);
			object.put("category", category);
			object.put("keyword", keyword);
			object.put("advanceSearch", advanceSearch);
			object.put("minRating", minRating);
			object.put("pageFrom", pageFrom);
			object.put("pageTo", pageTo);
			object.put("time", time);
			return object;
		} catch (JSONException e) {
			return new JSONObject();
		}
	}

	public static QuickSearch quickSearchFromJson(JSONObject object) {
		QuickSearch search = new QuickSearch();
		search.name = object.optString("name", null);
		search.mode = object.optInt("mode", 0);
		search.category = object.optInt("category", 0);
		search.keyword = object.optString("keyword", null);
		search.advanceSearch = object.optInt("advanceSearch", 0);
		search.minRating = object.optInt("minRating", 0);
		search.pageFrom = object.optInt("pageFrom", 0);
		search.pageTo = object.optInt("pageTo", 0);
		search.time = object.optLong("time", 0);
		return search;
	}

}
