package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Entity mapped to table "DOWNLOAD_LABELS".
 */
@Entity(tableName = "DOWNLOAD_LABELS")
public class DownloadLabel {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    private Long id;
    @androidx.annotation.Nullable
    @ColumnInfo(name = "LABEL")
    private String label;
    @ColumnInfo(name = "TIME")
    private long time;

    public DownloadLabel() {
    }

    @Ignore
    public DownloadLabel(Long id) {
        this.id = id;
    }

    @Ignore
    public DownloadLabel(Long id, String label, long time) {
        this.id = id;
        this.label = label;
        this.time = time;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

}
