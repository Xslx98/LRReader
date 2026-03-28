package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRArchiveApi;
import com.hippo.ehviewer.client.lrr.data.LRRArchive;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * GalleryProvider that fetches page images from a LANraragi server.
 *
 * Flow:
 * 1. start() → calls LRRArchiveApi.extractArchive() on IO thread to get page paths
 * 2. onRequest(index) → downloads the specific page image, decodes it, notifies UI
 * 3. Adjacent pages are preloaded (download only) for faster navigation
 */
public class LRRGalleryProvider extends GalleryProvider2 {

    private static final String TAG = "LRRGalleryProvider";
    private static final int BUFFER_SIZE = 65536;  // 64KB buffer for LAN transfers
    private static final int PRELOAD_COUNT = 5;     // Preload next 5 pages (LAN is fast)
    private static final long MAX_TOTAL_CACHE_BYTES = 500L * 1024L * 1024L; // 500MB total cache limit
    private static final String SP_CACHE_ACCESS = "lrr_cache_access"; // SharedPreferences name for cache access timestamps

    private final Context mContext;
    private final GalleryInfo mGalleryInfo;
    private final String mArcId;
    private final String mServerUrl;

    // Page paths returned by extractArchive()
    private volatile String[] mPagePaths;
    private volatile int mPageCount = GalleryProvider.STATE_WAIT;
    private volatile String mError;

    // Cache directory for downloaded pages
    private File mCacheDir;

    // Shared OkHttpClient for page downloads (created once, reused)
    private OkHttpClient mPageClient;

    // Track start page for reading progress
    private int mStartPage = 0;

    // Cooperative stop flag for background tasks
    private volatile boolean mStopped = false;

    public LRRGalleryProvider(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        mContext = context.getApplicationContext();
        mGalleryInfo = galleryInfo;
        mArcId = galleryInfo.token;  // arcid stored in token by toGalleryInfo()
        mServerUrl = LRRAuthManager.getServerUrl();
        // Load cached reading progress from local storage (instantly available)
        mStartPage = loadReadingProgress(mContext, galleryInfo.gid);
    }

    @Override
    public void start() {
        super.start();

        // Prepare cache directory
        mCacheDir = new File(mContext.getCacheDir(), "lrr_pages/" + mArcId);
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }

        // Record access time for LRU cache eviction (replaces unreliable File.lastModified())
        mContext.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE)
                .edit()
                .putLong(mArcId, System.currentTimeMillis())
                .apply();


        // Create shared OkHttpClient with longer timeout for page downloads
        OkHttpClient baseClient = EhApplication.getOkHttpClient(mContext);
        mPageClient = baseClient.newBuilder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        // Create .nomedia to prevent gallery apps from scanning cached images
        File noMedia = new File(mCacheDir, ".nomedia");
        if (!noMedia.exists()) {
            try { noMedia.createNewFile(); } catch (IOException ignored) {}
        }

        // Extract archive on background thread
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                OkHttpClient client = mPageClient != null ? mPageClient
                        : EhApplication.getOkHttpClient(mContext);
                String[] pages = (String[]) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRArchiveApi.getFileList(client, mServerUrl, mArcId, cont)
                );
                mPagePaths = pages;
                mPageCount = pages.length;
                Log.d(TAG, "Extracted " + mPageCount + " pages for " + mArcId);

                // Clear "new" flag
                try {
                    LRRCoroutineHelper.runSuspend(
                            (scope, cont) -> LRRArchiveApi.clearNewFlag(client, mServerUrl, mArcId, cont)
                    );
                } catch (Exception ignored) {}

                // Load server-side reading progress
                int serverPage = mStartPage; // fallback to local SP value
                try {
                    LRRArchive metadata = (LRRArchive) LRRCoroutineHelper.runSuspend(
                            (scope, cont) -> LRRArchiveApi.getArchiveMetadata(client, mServerUrl, mArcId, cont)
                    );
                    if (metadata.progress > 0) {
                        int serverPage0 = metadata.progress - 1; // convert 1-indexed to 0-indexed
                        if (serverPage0 > mStartPage) {
                            serverPage = serverPage0;
                            mStartPage = serverPage0;
                            saveReadingProgress(mContext, mGalleryInfo.gid, serverPage0);
                            Log.d(TAG, "Server progress newer: page " + serverPage0);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load server progress: " + e.getMessage());
                }

                // Notify UI that data is ready
                notifyDataChanged();

                // Preload pages from start page position
                preloadPages(serverPage);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract archive: " + e.getMessage(), e);
                mError = "Failed to load pages: " + e.getMessage();
                mPageCount = GalleryProvider.STATE_ERROR;
                notifyDataChanged();
            }
        });
    }

    @Override
    public void stop() {
        super.stop();
        mStopped = true;
        mPageClient = null;

        // Evict old archive caches if total size exceeds limit
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            cleanupOldCaches(mContext, MAX_TOTAL_CACHE_BYTES);
        });
    }

    /**
     * Evict oldest archive cache directories until total size is within limit.
     * Uses directory last-modified time as LRU indicator.
     */
    public static void cleanupOldCaches(Context context, long maxTotalBytes) {
        File parentDir = new File(context.getCacheDir(), "lrr_pages");
        if (!parentDir.exists() || !parentDir.isDirectory()) return;

        File[] archiveDirs = parentDir.listFiles();
        if (archiveDirs == null || archiveDirs.length == 0) return;

        // Calculate total size
        long totalSize = 0;
        java.util.List<File> dirList = new java.util.ArrayList<>(java.util.Arrays.asList(archiveDirs));
        for (File dir : dirList) {
            totalSize += getDirSize(dir);
        }

        if (totalSize <= maxTotalBytes) return;

        // Sort by SharedPreferences access time (oldest first), falling back to 0 for unrecorded dirs
        SharedPreferences sp = context.getSharedPreferences(SP_CACHE_ACCESS, Context.MODE_PRIVATE);
        dirList.sort((a, b) -> {
            long timeA = sp.getLong(a.getName(), 0L);
            long timeB = sp.getLong(b.getName(), 0L);
            return Long.compare(timeA, timeB);
        });

        SharedPreferences.Editor editor = sp.edit();
        for (File dir : dirList) {
            if (totalSize <= maxTotalBytes) break;
            long dirSize = getDirSize(dir);
            deleteDir(dir);
            editor.remove(dir.getName()); // Clean up stale SP entry
            totalSize -= dirSize;
            Log.d(TAG, "Evicted cache: " + dir.getName() + " (" + dirSize / 1024 + " KB)");
        }
        editor.apply();
    }

    private static long getDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.length();
            }
        }
        return size;
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @Override
    public int getStartPage() {
        return mStartPage;
    }

    @Override
    public void putStartPage(int page) {
        mStartPage = page;

        // Persist locally for instant restore on next open
        saveReadingProgress(mContext, mGalleryInfo.gid, page);

        // Sync progress to LANraragi server (1-indexed)
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                OkHttpClient client = EhApplication.getOkHttpClient(mContext);
                LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRArchiveApi.updateProgress(client, mServerUrl, mArcId, page + 1, cont)
                );
            } catch (Exception e) {
                Log.w(TAG, "Failed to sync progress: " + e.getMessage());
            }
        });
    }

    @Override
    public int size() {
        return mPageCount;
    }

    @NonNull
    @Override
    public String getImageFilename(int index) {
        return String.format(Locale.US, "lrr-%s-%04d", mArcId.substring(0, Math.min(8, mArcId.length())), index + 1);
    }

    @Override
    public boolean save(int index, @NonNull UniFile file) {
        File cached = getCacheFile(index);
        if (!cached.exists()) return false;
        try (FileInputStream fis = new FileInputStream(cached);
             java.io.OutputStream os = file.openOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save page " + index, e);
            return false;
        }
    }

    @Nullable
    @Override
    public UniFile save(int index, @NonNull UniFile dir, @NonNull String filename) {
        File cached = getCacheFile(index);
        if (!cached.exists()) return null;
        UniFile dst = dir.createFile(filename);
        if (dst == null) return null;
        if (save(index, dst)) {
            return dst;
        }
        return null;
    }

    @Override
    protected void onRequest(int index) {
        if (mPagePaths == null || index < 0 || index >= mPagePaths.length) {
            notifyPageFailed(index, "Page not available");
            return;
        }

        notifyPageWait(index);

        // Download and decode on IO thread
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                downloadAndDecodePage(index);

                // Preload adjacent pages after successful load
                preloadPages(index);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load page " + index + ": " + e.getMessage(), e);
                notifyPageFailed(index, e.getMessage());
            }
        });
    }

    @Override
    protected void onForceRequest(int index) {
        // Delete cached file and re-request
        File cached = getCacheFile(index);
        if (cached.exists()) {
            cached.delete();
        }
        onRequest(index);
    }

    @Override
    protected void onCancelRequest(int index) {
        // No-op for now (OkHttp doesn't support per-request cancellation easily here)
    }

    @Override
    public String getError() {
        return mError != null ? mError : "Unknown error";
    }

    // ==================== Internal ====================

    private File getCacheFile(int index) {
        return new File(mCacheDir, "page_" + index);
    }

    // Per-page lock to prevent concurrent downloads of the same page
    private final java.util.concurrent.ConcurrentHashMap<Integer, Object> mPageLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object getPageLock(int index) {
        return mPageLocks.computeIfAbsent(index, k -> new Object());
    }

    // Minimum valid image file size (1KB) — anything smaller is likely corrupt/empty
    private static final int MIN_IMAGE_SIZE = 1024;

    /**
     * Download a page to cache if not already cached.
     * Uses atomic write (temp file + fsync + rename) to prevent corruption.
     * Thread-safe: per-page locking prevents concurrent downloads of the same page.
     *
     * @param progressCallback if non-null, called with (index, percent) during download
     */
    private void downloadPageToCache(int index, @Nullable ProgressCallback progressCallback) throws IOException {
        File cacheFile = getCacheFile(index);
        if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) {
            return; // Already cached and sufficiently large
        }

        synchronized (getPageLock(index)) {
            // Check stop flag before downloading
            if (mStopped) return;
            // Double-check after acquiring lock
            if (cacheFile.exists() && cacheFile.length() > MIN_IMAGE_SIZE) {
                return;
            }

            // Delete any corrupt/tiny cached file before re-downloading
            if (cacheFile.exists()) {
                cacheFile.delete();
            }

            File tmpFile = new File(mCacheDir, "page_" + index + "." + Thread.currentThread().getId() + ".tmp");
            try {
                String pageUrl = mServerUrl + mPagePaths[index];
                // Use shared page client with 30s timeout (created once in start())
                OkHttpClient pageClient = mPageClient != null ? mPageClient
                        : EhApplication.getOkHttpClient(mContext);
                Request request = new Request.Builder()
                        .url(pageUrl)
                        .get()
                        .build();

                long contentLength = -1;
                long totalRead = 0;

                try (Response response = pageClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code());
                    }
                    contentLength = response.body().contentLength();

                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(tmpFile)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                            totalRead += read;
                            if (progressCallback != null && contentLength > 0) {
                                progressCallback.onProgress(index, (float) totalRead / contentLength);
                            }
                        }
                        // Flush to disk before rename to prevent reading incomplete data
                        fos.getFD().sync();
                    }
                }

                // Validate download completeness
                if (contentLength > 0 && totalRead != contentLength) {
                    tmpFile.delete();
                    throw new IOException("Incomplete download: expected " + contentLength + " bytes, got " + totalRead);
                }

                // Validate minimum size (catches empty/truncated chunked responses)
                if (totalRead < MIN_IMAGE_SIZE) {
                    tmpFile.delete();
                    throw new IOException("Downloaded file too small (" + totalRead + " bytes), likely corrupt");
                }

                // Validate image magic bytes
                if (!validateImageFile(tmpFile)) {
                    tmpFile.delete();
                    throw new IOException("Downloaded file is not a valid image (bad magic bytes)");
                }

                // Atomic rename
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.delete();
                    if (!cacheFile.exists() || cacheFile.length() < MIN_IMAGE_SIZE) {
                        throw new IOException("Failed to rename temp file");
                    }
                }
            } finally {
                // Clean up tmp file on any failure
                if (tmpFile.exists()) {
                    tmpFile.delete();
                }
            }
        }
    }

    /** Overload for preloading (no progress callback needed) */
    private void downloadPageToCache(int index) throws IOException {
        downloadPageToCache(index, null);
    }

    /**
     * Validate that a file starts with a known image format magic bytes.
     * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
     */
    private boolean validateImageFile(File file) {
        if (!file.exists() || file.length() < 4) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[16];
            int read = fis.read(header);
            if (read < 4) return false;

            // JPEG: FF D8 FF
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
                return true;
            }
            // PNG: 89 50 4E 47
            if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47) {
                return true;
            }
            // GIF: 47 49 46 38
            if (header[0] == (byte) 0x47 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46 && header[3] == (byte) 0x38) {
                return true;
            }
            // WebP: RIFF....WEBP
            if (read >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                return true;
            }
            // BMP: 42 4D
            if (header[0] == (byte) 0x42 && header[1] == (byte) 0x4D) {
                return true;
            }
            // AVIF: ....ftypavif (ftyp box at offset 4, major brand 'avif' at offset 8)
            if (read >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p'
                    && header[8] == 'a' && header[9] == 'v' && header[10] == 'i' && header[11] == 'f') {
                return true;
            }
            // JXL naked codestream: FF 0A
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0x0A) {
                return true;
            }
            // JXL ISOBMFF container: 00 00 00 0C 4A 58 4C 20 0D 0A 87 0A
            if (read >= 12 && header[0] == 0x00 && header[1] == 0x00 && header[2] == 0x00
                    && header[3] == 0x0C && header[4] == 0x4A && header[5] == 0x58
                    && header[6] == 0x4C && header[7] == 0x20 && header[8] == 0x0D
                    && header[9] == 0x0A && header[10] == (byte) 0x87 && header[11] == 0x0A) {
                return true;
            }

            Log.w(TAG, String.format("Unknown image format: %02X %02X %02X %02X", header[0], header[1], header[2], header[3]));
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Progress callback interface for download progress reporting */
    private interface ProgressCallback {
        void onProgress(int index, float percent);
    }

    private void downloadAndDecodePage(int index) throws IOException {
        // Try up to 2 times: once normally, once with cache invalidation
        for (int attempt = 0; attempt < 2; attempt++) {
            File cacheFile = getCacheFile(index);

            // On retry, wait 1s for network recovery, then force re-download
            if (attempt > 0) {
                Log.w(TAG, "Retry page " + index + " (attempt " + (attempt + 1) + "), waiting 1s...");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
            }

            // Download (with progress reporting via notifyPagePercent)
            downloadPageToCache(index, (idx, percent) -> notifyPagePercent(idx, percent));

            // Validate cached file before decode
            cacheFile = getCacheFile(index);
            if (!cacheFile.exists() || cacheFile.length() < MIN_IMAGE_SIZE) {
                if (attempt == 0) continue; // Retry
                notifyPageFailed(index, mContext.getString(R.string.lrr_download_failed_invalid));
                return;
            }

            if (!validateImageFile(cacheFile)) {
                cacheFile.delete();
                if (attempt == 0) continue; // Retry
                notifyPageFailed(index, mContext.getString(R.string.lrr_download_failed_not_image));
                return;
            }

            // Decode image
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                Image image = Image.decode(fis, false);
                if (image != null) {
                    notifyPageSucceed(index, image);
                    return; // Success!
                } else {
                    // Decode returned null — file is corrupt
                    cacheFile.delete();
                    if (attempt == 0) continue; // Retry
                    notifyPageFailed(index, mContext.getString(R.string.lrr_decode_failed));
                    return;
                }
            }
        }
    }

    /**
     * Preload adjacent pages (download only, no decode).
     * Downloads sequentially on a single IO thread to avoid overloading the server.
     */
    private void preloadPages(int currentIndex) {
        if (mPagePaths == null) return;
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            for (int i = 1; i <= PRELOAD_COUNT; i++) {
                if (mStopped) break;
                final int preloadIndex = currentIndex + i;
                if (preloadIndex >= mPagePaths.length) break;

                File cached = getCacheFile(preloadIndex);
                if (cached.exists() && cached.length() > MIN_IMAGE_SIZE) continue;

                try {
                    downloadPageToCache(preloadIndex);
                    Log.d(TAG, "Preloaded page " + preloadIndex);
                } catch (Exception e) {
                    Log.d(TAG, "Preload failed for page " + preloadIndex + ": " + e.getMessage());
                }
            }
        });
    }
}
