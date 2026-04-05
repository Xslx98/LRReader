/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.download;

import static com.hippo.util.FileUtils.getFileName;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.download.DownloadManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the local archive import pipeline (file picker, URI permission,
 * archive validation, and DownloadInfo creation) extracted from DownloadsScene.
 */
public class DownloadImportHelper {

    private static final String TAG = DownloadImportHelper.class.getSimpleName();
    private static final String REGISTRY_KEY = "download_import_file_picker";

    /**
     * Callback interface so the helper can interact with its host
     * (DownloadsScene) without a direct dependency.
     */
    public interface Callback {
        @Nullable Context getContext();
        @Nullable Activity getActivity();
        @Nullable DownloadManager getDownloadManager();
        @Nullable String getString(int resId);
        void onImportSuccess();
    }

    @NonNull
    private final Callback mCallback;

    @NonNull
    private final ActivityResultLauncher<Intent> mFilePickerLauncher;

    /**
     * Must be created before the host's onStart() to satisfy the
     * ActivityResultLauncher contract.
     *
     * @param callback      host callbacks
     * @param registry      the activity's ActivityResultRegistry
     * @param lifecycleOwner the host's LifecycleOwner (Fragment or Activity)
     */
    public DownloadImportHelper(@NonNull Callback callback,
                                @NonNull ActivityResultRegistry registry,
                                @NonNull LifecycleOwner lifecycleOwner) {
        mCallback = callback;
        mFilePickerLauncher = registry.register(
                REGISTRY_KEY,
                lifecycleOwner,
                new ActivityResultContracts.StartActivityForResult(),
                this::handleSelectedFile
        );
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Launches the system file picker for archive files.
     */
    public void importLocalArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Enable persistent URI permissions
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            String title = mCallback.getString(R.string.import_archive_title);
            mFilePickerLauncher.launch(Intent.createChooser(intent, title));
        } catch (Exception e) {
            showToast(R.string.import_archive_failed);
        }
    }

    // -------------------------------------------------------------------------
    // Internal pipeline
    // -------------------------------------------------------------------------

    private void handleSelectedFile(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }

        Context context = mCallback.getContext();
        if (context == null) {
            return;
        }

        // Request persistent URI permission immediately when file is selected
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "Successfully obtained persistent URI permission for: " + uri);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: " + uri, e);
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: " + uri, e);
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show();

        // Process the archive file in background
        com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(
                () -> processArchiveFile(uri));
    }

    private void processArchiveFile(Uri uri) {
        Context context = mCallback.getContext();
        if (context == null) {
            return;
        }

        try {
            // Verify URI accessibility (permission should already be granted)
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    runOnUiThread(() ->
                            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access file even with persistent permission", e);
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Get file name
            String fileName = getFileName(context, uri);
            if (fileName == null) {
                fileName = "imported_archive_" + System.currentTimeMillis();
            }

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create DownloadInfo for the archive
            DownloadInfo downloadInfo = createArchiveDownloadInfo(uri, fileName);
            if (downloadInfo == null) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Check if already imported
            DownloadManager downloadManager = mCallback.getDownloadManager();
            if (downloadManager != null && downloadManager.containDownloadInfo(downloadInfo.gid)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Add to download manager
            if (downloadManager != null) {
                List<DownloadInfo> downloadList = new ArrayList<>();
                downloadList.add(downloadInfo);
                downloadManager.addDownload(downloadList);
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show();
                    mCallback.onImportSuccess();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process archive file", e);
            runOnUiThread(() ->
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private boolean isValidArchiveFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr");
    }

    private DownloadInfo createArchiveDownloadInfo(Uri uri, String fileName) {
        try {
            DownloadInfo downloadInfo = new DownloadInfo();
            downloadInfo.gid = System.currentTimeMillis(); // Use timestamp as unique ID
            downloadInfo.token = "";
            downloadInfo.title = fileName.replaceAll("\\.[^.]*$", ""); // Remove extension
            downloadInfo.titleJpn = null;
            downloadInfo.thumb = null; // No thumbnail for imported archives
            downloadInfo.category = EhUtils.UNKNOWN; // Keep as UNKNOWN, will be handled in display logic
            downloadInfo.posted = null;
            downloadInfo.uploader = "Local Archive";
            downloadInfo.rating = -1.0f; // Keep default rating to not affect other downloads
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.legacy = 0;
            downloadInfo.time = System.currentTimeMillis();
            downloadInfo.label = null;
            downloadInfo.total = 0; // Will be set by archive provider
            downloadInfo.finished = 0;

            // Store the URI in the archiveUri field - this is the key identifier
            downloadInfo.archiveUri = uri.toString();

            return downloadInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DownloadInfo", e);
            return null;
        }
    }

    private void runOnUiThread(Runnable runnable) {
        Activity activity = mCallback.getActivity();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private void showToast(int resId) {
        Context context = mCallback.getContext();
        if (context != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
        }
    }
}
