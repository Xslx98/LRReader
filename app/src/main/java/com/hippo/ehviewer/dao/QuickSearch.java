package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.google.gson.JsonObject;

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

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.addProperty("name", name);
		object.addProperty("mode", mode);
		object.addProperty("category", category);
		object.addProperty("keyword", keyword);
		object.addProperty("advanceSearch", advanceSearch);
		object.addProperty("minRating", minRating);
		object.addProperty("pageFrom", pageFrom);
		object.addProperty("pageTo", pageTo);
		object.addProperty("time", time);
		return object;
	}

	public static QuickSearch quickSearchFromJson(JsonObject object) {
		QuickSearch search = new QuickSearch();
		search.name = object.has("name") ? object.get("name").getAsString() : null;
		search.mode = object.has("mode") ? object.get("mode").getAsInt() : 0;
		search.category = object.has("category") ? object.get("category").getAsInt() : 0;
		search.keyword = object.has("keyword") ? object.get("keyword").getAsString() : null;
		search.advanceSearch = object.has("advanceSearch") ? object.get("advanceSearch").getAsInt() : 0;
		search.minRating = object.has("minRating") ? object.get("minRating").getAsInt() : 0;
		search.pageFrom = object.has("pageFrom") ? object.get("pageFrom").getAsInt() : 0;
		search.pageTo = object.has("pageTo") ? object.get("pageTo").getAsInt() : 0;
		search.time = object.has("time") ? object.get("time").getAsLong() : 0;
		return search;
	}

}
