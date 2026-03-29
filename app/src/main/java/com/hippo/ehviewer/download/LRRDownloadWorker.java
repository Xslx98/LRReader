package com.hippo.ehviewer.download;

import com.hippo.ehviewer.settings.DownloadSettings;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.lrr.LRRAuthManager;
import com.hippo.ehviewer.client.lrr.LRRArchiveApi;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads all pages of a LANraragi archive to the download directory.
 * Replaces SpiderQueen's MODE_DOWNLOAD for LANraragi galleries.
 *
 * Reports progress via SpiderQueen.OnSpiderListener so DownloadManager
 * can update UI without any changes to its notification pipeline.
 */
public class LRRDownloadWorker {

    private static final String TAG = "LRRDownloadWorker";
    private static final int BUFFER_SIZE = 65536;  // 64KB for LAN
    private static final int MIN_IMAGE_SIZE = 1024; // 1KB minimum valid image
    private static final int MAX_RETRY = 2;         // Try up to 2 times per page

    private final Context mContext;
    private final DownloadInfo mInfo;
    private final String mArcId;
    private final String mServerUrl;

    private SpiderQueen.OnSpiderListener mListener;
    private volatile Thread mWorkerThread;
    private volatile boolean mCancelled;

    public LRRDownloadWorker(Context context, DownloadInfo info) {
        mContext = context.getApplicationContext();
        mInfo = info;
        mArcId = info.token;  // arcid stored in token
        mServerUrl = LRRAuthManager.getServerUrl();
    }

    public void setListener(SpiderQueen.OnSpiderListener listener) {
        mListener = listener;
    }

    public void start() {
        mCancelled = false;
        mWorkerThread = new Thread(this::doDownload, "LRRDownload-" + mArcId);
        mWorkerThread.start();
    }

    public void cancel() {
        mCancelled = true;
        Thread t = mWorkerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void doDownload() {
        OkHttpClient client = EhApplication.getOkHttpClient(mContext);
        // Use longer timeout for page downloads (large archives may need extraction time)
        OkHttpClient pageClient = client.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        String[] pagePaths;

        // Step 1: Extract archive to get page list
        try {
            pagePaths = (String[]) LRRCoroutineHelper.runSuspend(
                    (scope, cont) -> LRRArchiveApi.getFileList(client, mServerUrl, mArcId, cont)
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract archive", e);
            if (mListener != null) {
                mListener.onPageFailure(0, "Extract failed: " + e.getMessage(), 0, 0, 0);
                mListener.onFinish(0, 0, 0);
            }
            return;
        }

        if (mCancelled) return;

        int total = pagePaths.length;
        if (mListener != null) {
            mListener.onGetPages(total);
        }

        // Step 2: Prepare download directory
        File downloadDir = getDownloadDir();
        if (downloadDir == null) {
            if (mListener != null) {
                mListener.onPageFailure(0, "Cannot create download directory", 0, 0, total);
                mListener.onFinish(0, 0, total);
            }
            return;
        }

        // Create .nomedia
        File noMedia = new File(downloadDir, ".nomedia");
        if (!noMedia.exists()) {
            try { noMedia.createNewFile(); } catch (IOException ignored) {}
        }

        // Step 3: Download each page
        int finished = 0;
        int downloaded = 0;

        for (int i = 0; i < total; i++) {
            if (mCancelled || Thread.currentThread().isInterrupted()) {
                break;
            }

            // Determine file extension from path
            String pagePath = pagePaths[i];
            String ext = getExtension(pagePath);
            File pageFile = new File(downloadDir, String.format("%04d%s", i + 1, ext));

            // Skip if already downloaded and valid
            if (pageFile.exists() && pageFile.length() > MIN_IMAGE_SIZE && validateImageFile(pageFile)) {
                finished++;
                downloaded++;
                if (mListener != null) {
                    mListener.onPageSuccess(i, finished, downloaded, total);
                }
                continue;
            }

            // Download the page with retry
            boolean success = false;
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                if (mCancelled) break;

                // On retry, delete potentially corrupt file
                if (attempt > 0) {
                    Log.w(TAG, "Retry page " + i + " (attempt " + (attempt + 1) + ")");
                    if (pageFile.exists()) {
                        pageFile.delete();
                    }
                }

                try {
                    downloadPage(pageClient, pagePath, pageFile, i, total);
                    // Validate after download
                    if (!pageFile.exists() || pageFile.length() < MIN_IMAGE_SIZE) {
                        if (pageFile.exists()) pageFile.delete();
                        throw new IOException("Downloaded file too small or missing");
                    }
                    if (!validateImageFile(pageFile)) {
                        pageFile.delete();
                        throw new IOException("Downloaded file is not a valid image");
                    }
                    success = true;
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "Failed to download page " + i + " (attempt " + (attempt + 1) + ")", e);
                    if (attempt == MAX_RETRY - 1) {
                        // Final attempt failed
                        if (mListener != null) {
                            mListener.onPageFailure(i, e.getMessage(), finished, downloaded, total);
                        }
                    }
                }
            }

            if (success) {
                finished++;
                downloaded++;
                if (mListener != null) {
                    mListener.onPageSuccess(i, finished, downloaded, total);
                }
            }
        }

        // Step 4: Report finish
        if (mListener != null) {
            mListener.onFinish(finished, downloaded, total);
        }
    }

    private void downloadPage(OkHttpClient client, String pagePath, File outFile,
                              int index, int total) throws IOException {
        String pageUrl = mServerUrl + pagePath;
        Request request = new Request.Builder()
                .url(pageUrl)
                .get()
                .build();

        File tmpFile = new File(outFile.getParent(), outFile.getName() + "." + Thread.currentThread().getId() + ".tmp");

        try {
            long contentLength = -1;
            long totalRead = 0;

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }

                contentLength = response.body().contentLength();
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        if (mCancelled) {
                            tmpFile.delete();
                            throw new IOException("Cancelled");
                        }
                        fos.write(buffer, 0, read);
                        totalRead += read;
                        if (mListener != null && contentLength > 0) {
                            mListener.onPageDownload(index, contentLength, totalRead, read);
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

            // Atomic rename
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete();
                if (!outFile.exists() || outFile.length() < MIN_IMAGE_SIZE) {
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

    /**
     * Validate that a file starts with a known image format magic bytes.
     * Supports JPEG, PNG, GIF, WebP, BMP, AVIF, JPEG XL.
     */
    private static boolean validateImageFile(File file) {
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

    private File getDownloadDir() {
        // Use the same download location as SpiderDen (DownloadSettings.getDownloadLocation())
        // so downloaded files are visible in the user-configured directory
        try {
            UniFile uniDir = SpiderDen.getGalleryDownloadDir(mInfo);
            if (uniDir != null && uniDir.ensureDir()) {
                Uri uri = uniDir.getUri();
                if ("file".equals(uri.getScheme())) {
                    return new File(uri.getPath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get gallery download dir from SpiderDen, using fallback", e);
        }
        // Fallback: app-private external files directory
        File baseDir = new File(mContext.getExternalFilesDir(null), "download");
        if (!baseDir.exists()) baseDir.mkdirs();
        File archiveDir = new File(baseDir, sanitizeFilename(mInfo.title));
        if (!archiveDir.exists()) archiveDir.mkdirs();
        return archiveDir;
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "unknown";
        // Remove characters invalid in filenames
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            return path.substring(dot);
        }
        return ".jpg";
    }
}
