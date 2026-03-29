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

package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaScannerConnection;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.ServiceRegistry;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.settings.DownloadSettings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.yorozuya.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

    public static final int REQUEST_CODE_PICK_IMAGE_DIR = 0;
    public static final int REQUEST_CODE_PICK_IMAGE_DIR_L = 1;
    private static final int REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE = 2;

    public static final String KEY_DOWNLOAD_LOCATION = "download_location";
    public static final String KEY_EXPORT_DOWNLOAD_ITEMS = "export_download_items";
    public static final String KEY_IMPORT_DOWNLOAD_ITEMS = "import_download_items";
    public static final String KEY_CLEAN_INVALID_DOWNLOAD = "clean_invalid_download";

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private Preference mDownloadLocation;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.download_settings);

        Preference mediaScan = findPreference(DownloadSettings.KEY_MEDIA_SCAN);
        Preference downloadTimeout = findPreference(DownloadSettings.KEY_DOWNLOAD_TIMEOUT);
        mDownloadLocation = findPreference(KEY_DOWNLOAD_LOCATION);
        Preference exportDownloadItems = findPreference(KEY_EXPORT_DOWNLOAD_ITEMS);
        Preference importDownloadItems = findPreference(KEY_IMPORT_DOWNLOAD_ITEMS);
        Preference cleanInvalidDownload = findPreference(KEY_CLEAN_INVALID_DOWNLOAD);
        Preference preloadImage = findPreference("preload_image");

        onUpdateDownloadLocation();

        // Initialize summaries with current settings
        if (downloadTimeout != null) {
            String timeoutStr = DownloadSettings.getDownloadTimeout() == 0 ? getString(R.string.download_timeout_unlimited) : String.valueOf(DownloadSettings.getDownloadTimeout());
            downloadTimeout.setSummary(getString(R.string.settings_download_timeout_summary, timeoutStr));
        }
        if(preloadImage != null){
            preloadImage.setSummary(getString(R.string.settings_download_preload_image_summary, String.valueOf(DownloadSettings.getPreloadImage())));
        }


        if (mediaScan != null) {
            mediaScan.setOnPreferenceChangeListener(this);
        }
        if (downloadTimeout != null) {
            downloadTimeout.setOnPreferenceChangeListener(this);
        }

        if (mDownloadLocation != null) {
            mDownloadLocation.setOnPreferenceClickListener(this);
        }
        if (exportDownloadItems != null) {
            exportDownloadItems.setOnPreferenceClickListener(this);
        }
        if (importDownloadItems != null) {
            importDownloadItems.setOnPreferenceClickListener(this);
        }
        if (cleanInvalidDownload != null) {
            cleanInvalidDownload.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDownloadLocation = null;
    }

    public void onUpdateDownloadLocation() {
        UniFile file = DownloadSettings.getDownloadLocation();
        if (mDownloadLocation != null) {
            if (file != null) {
                mDownloadLocation.setSummary(file.getUri().toString());
            } else {
                mDownloadLocation.setSummary(R.string.settings_download_invalid_download_location);
            }
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (KEY_DOWNLOAD_LOCATION.equals(key)) {
            openDirPickerL();
            return true;
        } else if (KEY_EXPORT_DOWNLOAD_ITEMS.equals(key)) {
            exportDownloadItems();
            return true;
        } else if (KEY_IMPORT_DOWNLOAD_ITEMS.equals(key)) {
            importDownloadItems();
            return true;
        } else if (KEY_CLEAN_INVALID_DOWNLOAD.equals(key)) {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.settings_download_clean_invalid_download)
                    .setMessage(R.string.settings_download_clean_invalid_download_confirm)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> executeCleanInvalidDownload())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return false;
    }

    private void openDirPickerL() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE_DIR_L);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(getActivity(), R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDownloadItems() {
        List<GalleryInfo> list = ServiceRegistry.INSTANCE.getDataModule().getDownloadManager().getDownloadInfoList();
        if (list.isEmpty()) {
            Toast.makeText(getActivity(), R.string.settings_download_export_no_items, Toast.LENGTH_SHORT).show();
            return;
        }

        UniFile dir = DownloadSettings.getDownloadLocation();
        if (dir == null) {
            Toast.makeText(getActivity(), R.string.settings_download_invalid_download_location, Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
        String fileName = "lrreader-download-" + sdf.format(new Date()) + ".csv";

        UniFile file = dir.createFile(fileName);
        if (file == null) {
            Toast.makeText(getActivity(), R.string.settings_download_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        try (OutputStream os = file.openOutputStream()) {
            os.write(DownloadManager.DOWNLOAD_INFO_HEADER.getBytes(StandardCharsets.UTF_8));
            for (GalleryInfo gi : list) {
                os.write(gi.toCSV().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(getActivity(), getString(R.string.settings_download_export_succeed, file.getUri().toString()), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getActivity(), R.string.settings_download_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void importDownloadItems() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(getActivity(), R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(data == null){
            super.onActivityResult(requestCode, resultCode, null);
            return;
        }
        switch (requestCode) {
            case REQUEST_CODE_PICK_IMAGE_DIR: {
                if (resultCode == Activity.RESULT_OK) {
                    UniFile uniFile = UniFile.fromUri(getActivity(), data.getData());
                    if (uniFile != null) {
                        DownloadSettings.putDownloadLocation(uniFile);
                        onUpdateDownloadLocation();
                    } else {
                        Toast.makeText(getActivity(), R.string.settings_download_cant_get_download_location,
                                Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            }
            case REQUEST_CODE_PICK_IMAGE_DIR_L: {
                if (resultCode == Activity.RESULT_OK) {
                    Uri treeUri = data.getData();
                    if (treeUri != null) {
                        requireActivity().getContentResolver().takePersistableUriPermission(
                                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        UniFile uniFile = UniFile.fromTreeUri(getActivity(), treeUri);
                        if (uniFile != null) {
                            DownloadSettings.putDownloadLocation(uniFile);
                            onUpdateDownloadLocation();
                        } else {
                            Toast.makeText(getActivity(), R.string.settings_download_cant_get_download_location,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                break;
            }
            case REQUEST_CODE_PICK_DOWNLOAD_IMPORT_FILE: {
                if (resultCode == Activity.RESULT_OK) {
                    executeImportDownload(data.getData());
                }
                break;
            }
            default: {
                super.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (DownloadSettings.KEY_MEDIA_SCAN.equals(key)) {
            if (newValue instanceof Boolean) {
                UniFile downloadLocation = DownloadSettings.getDownloadLocation();
                if ((Boolean) newValue) {
                    CommonOperations.removeNoMediaFile(downloadLocation);
                    // Trigger MediaStore re-scan so images appear in Photos/Gallery
                    triggerMediaScan(downloadLocation);
                } else {
                    CommonOperations.ensureNoMediaFile(downloadLocation);
                }
            }
            return true;
        } else if (DownloadSettings.KEY_DOWNLOAD_TIMEOUT.equals(key)) {
            if (newValue instanceof String) {
                DownloadSettings.setDownloadTimeout(toTimeoutTime(newValue));
            }
            return true;
        }
        return false;
    }

    private int toTimeoutTime(Object newValue) {
        try{
            return Integer.parseInt(newValue.toString());
        }catch (NumberFormatException e){
            return 0;
        }
    }

    // --- Import download task (replaces AsyncTask) ---

    @SuppressWarnings("deprecation")
    private void executeImportDownload(Uri uri) {
        if (getActivity() == null || uri == null) return;

        ProgressDialog dialog = new ProgressDialog(getActivity());
        dialog.setTitle(R.string.settings_download_import_items);
        dialog.setIndeterminate(false);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            int importCount = 0;
            try (InputStream is = requireActivity().getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    mainHandler.post(() -> dismissAndShowResult(dialog, 0));
                    return;
                }
                String content = IOUtils.readString(is, StandardCharsets.UTF_8.name());
                String[] lines = content.split("\n");
                List<GalleryInfo> galleryInfos = new ArrayList<>();
                for (String line : lines) {
                    if (line.startsWith(DownloadManager.DOWNLOAD_INFO_HEADER)) {
                        continue;
                    }
                    GalleryInfo gi = GalleryInfo.fromCSV(line);
                    if (gi != null) {
                        galleryInfos.add(gi);
                    }
                }

                DownloadManager downloadManager = ServiceRegistry.INSTANCE.getDataModule().getDownloadManager();
                int total = galleryInfos.size();
                mainHandler.post(() -> { dialog.setMax(total); dialog.setProgress(0); });

                for (int i = 0; i < total; i++) {
                    GalleryInfo gi = galleryInfos.get(i);
                    if (downloadManager.getDownloadInfo(gi.gid) == null) {
                        downloadManager.addDownload(gi, null);
                        importCount++;
                    }
                    final int progress = i + 1;
                    mainHandler.post(() -> dialog.setProgress(progress));
                }
            } catch (IOException e) {
                // importCount stays 0
            }
            final int result = importCount;
            mainHandler.post(() -> dismissAndShowResult(dialog, result));
        });
    }

    @SuppressWarnings("deprecation")
    private void dismissAndShowResult(ProgressDialog dialog, int result) {
        if (isAdded() && getActivity() != null) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (IllegalArgumentException e) {
                ExceptionUtils.throwIfFatal(e);
            }
            if (result > 0) {
                Toast.makeText(getActivity(), getString(R.string.settings_download_import_succeed, result), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(), R.string.settings_download_import_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Clean invalid download task (replaces AsyncTask) ---

    @SuppressWarnings("deprecation")
    private void executeCleanInvalidDownload() {
        if (getActivity() == null) return;

        ProgressDialog dialog = new ProgressDialog(getActivity());
        dialog.setTitle(R.string.settings_download_cleaning);
        dialog.setIndeterminate(false);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            List<String> logs = new ArrayList<>();
            int invalidCount = 0;

            UniFile downloadDir = DownloadSettings.getDownloadLocation();
            if (downloadDir == null || !downloadDir.isDirectory()) {
                mainHandler.post(() -> dismissAndShowCleanResult(dialog, 0));
                return;
            }

            UniFile[] files = downloadDir.listFiles();
            if (files == null) {
                mainHandler.post(() -> dismissAndShowCleanResult(dialog, 0));
                return;
            }

            int total = files.length;
            mainHandler.post(() -> { dialog.setMax(total); dialog.setProgress(0); });

            DownloadManager downloadManager = ServiceRegistry.INSTANCE.getDataModule().getDownloadManager();

            for (int i = 0; i < total; i++) {
                UniFile dir = files[i];
                final int progress = i + 1;
                mainHandler.post(() -> dialog.setProgress(progress));

                if (!dir.isDirectory()) {
                    continue;
                }

                UniFile[] subFiles = dir.listFiles();
                if (subFiles == null || subFiles.length == 0) {
                    logs.add("Empty directory: " + dir.getName());
                    invalidCount++;
                    dir.delete();
                    continue;
                }

                UniFile ehViewerFile = dir.findFile(DownloadManager.DOWNLOAD_INFO_FILENAME);
                if (ehViewerFile == null) {
                    logs.add("Missing .ehviewer file: " + dir.getName());
                    invalidCount++;
                    continue;
                }

                try {
                    String content = IOUtils.readString(ehViewerFile.openInputStream(), StandardCharsets.UTF_8.name());
                    String[] contentLines = content.split("\n");
                    if (contentLines.length < 8) {
                        logs.add("Invalid .ehviewer file: " + dir.getName());
                        invalidCount++;
                        long gid;
                        try {
                            gid = Long.parseLong(contentLines[0]);
                        } catch (NumberFormatException e) {
                            gid = -1;
                        }
                        if (gid != -1) {
                            com.hippo.ehviewer.dao.DownloadInfo gi = downloadManager.getDownloadInfo(gid);
                            if (gi != null) {
                                gi.state = com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE;
                                EhDB.putDownloadInfo(gi);
                            }
                        }
                        continue;
                    }
                    int pageCount = Integer.parseInt(contentLines[7]);
                    int imageFileCount = 0;
                    for (UniFile subFile : subFiles) {
                        String name = subFile.getName();
                        if (name != null && !name.startsWith(".")) {
                            imageFileCount++;
                        }
                    }

                    if (imageFileCount != pageCount) {
                        logs.add("Inconsistent file count: " + dir.getName() + ", expected: " + pageCount + ", actual: " + imageFileCount);
                        invalidCount++;
                        for (UniFile subFile : subFiles) {
                            String name = subFile.getName();
                            if (name != null && !name.equals(DownloadManager.DOWNLOAD_INFO_FILENAME) && !name.startsWith(".")) {
                                subFile.delete();
                            }
                        }
                        long gid;
                        try {
                            gid = Long.parseLong(contentLines[0]);
                        } catch (NumberFormatException e) {
                            gid = -1;
                        }
                        if (gid != -1) {
                            com.hippo.ehviewer.dao.DownloadInfo gi = downloadManager.getDownloadInfo(gid);
                            if (gi != null) {
                                gi.state = com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE;
                                EhDB.putDownloadInfo(gi);
                            }
                        }
                    }
                } catch (IOException | NumberFormatException e) {
                    logs.add("Error processing directory: " + dir.getName() + " - " + e.getMessage());
                    invalidCount++;
                }
            }

            if (!logs.isEmpty()) {
                saveCleanLog(downloadDir, logs);
            }

            final int resultCount = invalidCount;
            mainHandler.post(() -> dismissAndShowCleanResult(dialog, resultCount));
        });
    }

    @SuppressWarnings("deprecation")
    private void dismissAndShowCleanResult(ProgressDialog dialog, int result) {
        if (isAdded() && getActivity() != null) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (IllegalArgumentException e) {
                ExceptionUtils.throwIfFatal(e);
            }
            if (result > 0) {
                Toast.makeText(getActivity(), getString(R.string.settings_download_clean_invalid_done, result), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getActivity(), R.string.settings_download_clean_invalid_no_invalid, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveCleanLog(UniFile downloadDir, List<String> logs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm", Locale.US);
        String fileName = "delfile-" + sdf.format(new Date()) + ".log";
        UniFile logFile = downloadDir.createFile(fileName);
        if (logFile != null) {
            try (OutputStream os = logFile.openOutputStream()) {
                for (String log : logs) {
                    os.write((log + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Trigger MediaStore re-scan for all image files in the download directory.
     * This is needed after removing .nomedia so that Photos/Gallery apps discover the files.
     */
    private void triggerMediaScan(UniFile downloadLocation) {
        if (downloadLocation == null || getActivity() == null) {
            return;
        }

        Activity activity = getActivity();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            List<String> paths = new ArrayList<>();
            collectImagePaths(downloadLocation, paths);
            if (!paths.isEmpty()) {
                MediaScannerConnection.scanFile(
                        activity.getApplicationContext(),
                        paths.toArray(new String[0]),
                        null,
                        null
                );
            }
        });
    }

    private void collectImagePaths(UniFile dir, List<String> paths) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        UniFile[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (UniFile f : files) {
            if (f.isDirectory()) {
                collectImagePaths(f, paths);
            } else {
                String name = f.getName();
                if (name != null) {
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                            lower.endsWith(".png") || lower.endsWith(".gif") ||
                            lower.endsWith(".webp")) {
                        // Extract file path from URI for MediaScanner
                        Uri uri = f.getUri();
                        if (uri != null && "file".equals(uri.getScheme())) {
                            String path = uri.getPath();
                            if (path != null) {
                                paths.add(path);
                            }
                        }
                    }
                }
            }
        }
    }
}
