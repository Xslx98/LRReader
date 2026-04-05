package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hippo.app.EditTextDialogBuilder;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.lrr.LRRArchiveApi;
import com.hippo.ehviewer.client.lrr.LRRClientProvider;
import com.hippo.ehviewer.client.lrr.LRRCoroutineHelper;
import com.hippo.ehviewer.client.lrr.LRRMiscApi;
import com.hippo.ehviewer.ui.scene.BaseScene;
import com.hippo.util.IoThreadPoolExecutor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Handles archive upload and URL download operations for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
public class GalleryUploadHelper {

    private static final String TAG = "GalleryUploadHelper";

    public interface Callback {
        void showTip(String message, int length);
        void showTip(int resId, int length);
        void refreshList();
        @Nullable Activity getHostActivity();
        @Nullable Context getHostContext();
        String getHostString(int resId);
        String getHostString(int resId, Object... formatArgs);
        void startActivityForResult(Intent intent, int requestCode);
    }

    private final Callback mCallback;

    public GalleryUploadHelper(Callback callback) {
        mCallback = callback;
    }

    /**
     * Launch file picker for archive upload (ZIP, RAR, CBZ, CB7, etc.).
     */
    public void showUploadFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {
            "application/zip", "application/x-rar-compressed",
            "application/x-7z-compressed", "application/x-tar",
            "application/gzip", "application/octet-stream"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            mCallback.startActivityForResult(
                Intent.createChooser(intent,
                    mCallback.getHostString(R.string.lrr_upload_choose_file)),
                GalleryListScene.REQUEST_CODE_UPLOAD_ARCHIVE);
        } catch (Exception e) {
            mCallback.showTip(R.string.lrr_upload_no_file_manager, BaseScene.LENGTH_SHORT);
        }
    }

    /**
     * Handle the selected file for archive upload.
     */
    public void handleUploadResult(Uri uri) {
        Context context = mCallback.getHostContext();
        if (context == null) return;

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            File tempFile = null;
            try {
                String fileName = getFileNameFromUri(context, uri);
                if (fileName == null) fileName = "upload_archive";
                tempFile = new File(context.getCacheDir(), fileName);
                try (InputStream is = context.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    if (is == null) throw new IOException("Cannot open file");
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }

                File finalTempFile = tempFile;
                String arcid = (String) LRRCoroutineHelper.runSuspend(
                    (scope, cont) -> LRRArchiveApi.uploadArchive(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        finalTempFile, null, null, null, cont)
                );

                Activity activity = mCallback.getHostActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        mCallback.showTip(
                            mCallback.getHostString(R.string.lrr_upload_success),
                            BaseScene.LENGTH_LONG);
                        mCallback.refreshList();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload failed", e);
                Activity activity = mCallback.getHostActivity();
                if (activity != null) {
                    activity.runOnUiThread(() ->
                        mCallback.showTip(
                            mCallback.getHostString(R.string.lrr_upload_failed, e.getMessage()),
                            BaseScene.LENGTH_LONG));
                }
            } finally {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        });
    }

    /**
     * Show dialog for URL download on the LANraragi server.
     */
    public void showUrlDownloadDialog() {
        Context context = mCallback.getHostContext();
        if (context == null) return;

        EditTextDialogBuilder builder = new EditTextDialogBuilder(
            context, null, mCallback.getHostString(R.string.lrr_url_download_hint));
        builder.setTitle(mCallback.getHostString(R.string.lrr_url_download_title));
        builder.setPositiveButton(mCallback.getHostString(android.R.string.ok), (dialog, which) -> {
            String url = builder.getText().trim();
            if (url.isEmpty()) {
                mCallback.showTip(R.string.lrr_url_download_empty, BaseScene.LENGTH_SHORT);
                return;
            }
            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                try {
                    int jobId = (int) LRRCoroutineHelper.runSuspend(
                        (scope, cont) -> LRRMiscApi.downloadUrl(
                            LRRClientProvider.getClient(),
                            LRRClientProvider.getBaseUrl(),
                            url, null, cont)
                    );
                    Activity activity = mCallback.getHostActivity();
                    if (activity != null) {
                        activity.runOnUiThread(() ->
                            mCallback.showTip(
                                mCallback.getHostString(R.string.lrr_url_download_success, jobId),
                                BaseScene.LENGTH_LONG));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "URL download failed", e);
                    Activity activity = mCallback.getHostActivity();
                    if (activity != null) {
                        activity.runOnUiThread(() ->
                            mCallback.showTip(
                                mCallback.getHostString(R.string.lrr_url_download_failed, e.getMessage()),
                                BaseScene.LENGTH_LONG));
                    }
                }
            });
        });
        builder.show();
    }

    @Nullable
    private String getFileNameFromUri(Context context, Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        }
        return name;
    }
}
