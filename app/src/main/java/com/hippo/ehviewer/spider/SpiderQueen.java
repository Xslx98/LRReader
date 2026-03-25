/*
 * STUB (LANraragi: E-Hentai spider system removed)
 * LANraragi uses LRRGalleryProvider / LRRDownloadWorker instead.
 */

package com.hippo.ehviewer.spider;

import android.content.Context;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * SpiderQueen — STUB.
 * E-Hentai image downloading spider has been removed.
 * Only preserved: constants, OnSpiderListener interface, findStartPage().
 */
public final class SpiderQueen implements Runnable {

    public static final int MODE_READ = 0;
    public static final int MODE_DOWNLOAD = 1;
    public static final int STATE_NONE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_FINISHED = 2;
    public static final int STATE_FAILED = 3;
    public static final int DECODE_THREAD_NUM = 2;
    public static final String SPIDER_INFO_FILENAME = ".ehviewer";
    public static final String SPIDER_INFO_BACKUP_DIR = "backupDir";

    private SpiderQueen() {}

    @UiThread
    public static SpiderQueen obtainSpiderQueen(@NonNull Context context,
            @NonNull GalleryInfo galleryInfo, @Mode int mode) {
        return new SpiderQueen();
    }

    @UiThread
    public static void releaseSpiderQueen(@NonNull SpiderQueen queen, @Mode int mode) {
        // No-op
    }

    @UiThread
    public static int findStartPage(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        return 0;
    }

    public void addOnSpiderListener(OnSpiderListener listener) {}
    public void removeOnSpiderListener(OnSpiderListener listener) {}

    public int size() { return GalleryProvider.STATE_ERROR; }
    public String getError() { return "E-Hentai spider removed"; }
    public Object request(int index) { return null; }
    public Object forceRequest(int index) { return null; }
    public void cancelRequest(int index) {}
    public int getStartPage() { return 0; }
    public void putStartPage(int page) {}

    public boolean save(int index, @NonNull UniFile file) { return false; }
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) { return null; }

    @Override
    public void run() {}

    @IntDef({MODE_READ, MODE_DOWNLOAD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    @IntDef({STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    public interface OnSpiderListener {
        void onGetPages(int pages);
        void onGet509(int index);
        void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead);
        void onPageSuccess(int index, int finished, int downloaded, int total);
        void onPageFailure(int index, String error, int finished, int downloaded, int total);
        void onFinish(int finished, int downloaded, int total);
        void onGetImageSuccess(int index, Image image);
        void onGetImageFailure(int index, String error);
    }
}
