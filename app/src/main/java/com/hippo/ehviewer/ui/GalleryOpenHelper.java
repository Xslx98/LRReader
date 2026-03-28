package com.hippo.ehviewer.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.unifile.UniFile;

import java.io.File;

/**
 * Shared utility for building the optimal Intent to open a gallery for reading.
 * <p>
 * If local downloaded files exist for the given archive, opens with {@code ACTION_DIR}
 * (instant, offline). Otherwise falls back to {@code ACTION_LRR} (server streaming).
 */
public final class GalleryOpenHelper {

    private GalleryOpenHelper() {} // utility class

    /**
     * Build an Intent for reading the given gallery, preferring local files if available.
     *
     * @param context    Context
     * @param galleryInfo Gallery to open
     * @return Intent ready for startActivity()
     */
    @NonNull
    public static Intent buildReadIntent(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        Intent intent = new Intent(context, GalleryActivity.class);

        // Check if local downloaded files exist
        File downloadDir = getLocalDownloadDir(context, galleryInfo);
        if (downloadDir != null && hasImageFiles(downloadDir)) {
            // Local files available — read offline (instant)
            intent.setAction(GalleryActivity.ACTION_DIR);
            intent.putExtra(GalleryActivity.KEY_FILENAME, downloadDir.getAbsolutePath());
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, galleryInfo);
        } else {
            // No local files — stream from LANraragi server
            intent.setAction(GalleryActivity.ACTION_LRR);
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, galleryInfo);
        }

        return intent;
    }

    /**
     * Get the local download directory for a gallery, if it exists.
     * Uses SpiderDen.getGalleryDownloadDir() for consistency with LRRDownloadWorker.
     */
    @Nullable
    public static File getLocalDownloadDir(@NonNull Context context, @NonNull GalleryInfo info) {
        UniFile uniDir = SpiderDen.getGalleryDownloadDir(info);
        if (uniDir != null) {
            Uri uri = uniDir.getUri();
            if ("file".equals(uri.getScheme())) {
                File dir = new File(uri.getPath());
                if (dir.isDirectory()) {
                    return dir;
                }
            }
        }
        // Fallback: check old app-private path for backwards compatibility
        if (info.title == null) return null;
        File baseDir = new File(context.getExternalFilesDir(null), "download");
        String dirName = info.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        File oldDir = new File(baseDir, dirName);
        return oldDir.isDirectory() ? oldDir : null;
    }

    /**
     * Check if a directory contains at least one image file.
     */
    public static boolean hasImageFiles(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile()) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".gif") ||
                    name.endsWith(".webp") || name.endsWith(".bmp")) {
                    return true;
                }
            }
        }
        return false;
    }
}
