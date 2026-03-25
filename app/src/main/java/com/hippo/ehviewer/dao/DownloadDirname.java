package com.hippo.ehviewer.dao;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Entity mapped to table "DOWNLOAD_DIRNAME".
 */
@Entity(tableName = "DOWNLOAD_DIRNAME")
public class DownloadDirname {

    @PrimaryKey
    @ColumnInfo(name = "GID")
    private long gid;
    @androidx.annotation.Nullable
    @ColumnInfo(name = "DIRNAME")
    private String dirname;

    public DownloadDirname() {
    }

    @Ignore
    public DownloadDirname(long gid) {
        this.gid = gid;
    }

    @Ignore
    public DownloadDirname(long gid, String dirname) {
        this.gid = gid;
        this.dirname = dirname;
    }

    public long getGid() {
        return gid;
    }

    public void setGid(long gid) {
        this.gid = gid;
    }

    public String getDirname() {
        return dirname;
    }

    public void setDirname(String dirname) {
        this.dirname = dirname;
    }

}
