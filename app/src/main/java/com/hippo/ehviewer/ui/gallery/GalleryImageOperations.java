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

package com.hippo.ehviewer.ui.gallery;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.settings.ReadingSettings;
import com.hippo.unifile.UniFile;
import com.hippo.util.ExceptionUtils;
import com.hippo.lib.yorozuya.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles image save, share, and page dialog operations
 * for the gallery reader. Extracted from GalleryActivity.
 */
public class GalleryImageOperations {

    @NonNull private final Activity mActivity;
    @Nullable private GalleryProvider2 mGalleryProvider;
    @Nullable private GalleryInfo mGalleryInfo;
    @Nullable private ActivityResultLauncher<Intent> mSaveToLauncher;

    private String mCacheFileName;

    public GalleryImageOperations(@NonNull Activity activity) {
        mActivity = activity;
    }

    public void setGalleryProvider(@Nullable GalleryProvider2 provider) {
        mGalleryProvider = provider;
    }

    public void setGalleryInfo(@Nullable GalleryInfo info) {
        mGalleryInfo = info;
    }

    public void setSaveToLauncher(@Nullable ActivityResultLauncher<Intent> launcher) {
        mSaveToLauncher = launcher;
    }

    // --- Share ---

    public void shareImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        File dir = AppConfig.getExternalTempDir();
        if (null == dir) {
            Toast.makeText(mActivity, R.string.error_cant_create_temp_file, Toast.LENGTH_SHORT).show();
            return;
        }
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir), mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }

        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(filename));
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "image/jpeg";
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, file.getUri());
        intent.setType(mimeType);

        try {
            mActivity.startActivity(Intent.createChooser(intent, mActivity.getString(R.string.share_image)));
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(mActivity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Save to MediaStore ---

    public void saveImage(int page) {
        if (null == mGalleryProvider) {
            return;
        }

        File cacheDir = mActivity.getCacheDir();
        if (null == cacheDir) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = mGalleryProvider.getImageFilename(page);
        UniFile tempFile = mGalleryProvider.save(page, UniFile.fromFile(cacheDir), filename);
        if (null == tempFile) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }

        // Build display name with archive title prefix
        String archiveTitle = (mGalleryInfo != null && mGalleryInfo.title != null)
                ? sanitizeFilename(mGalleryInfo.title, 80) : "archive";
        String displayName = archiveTitle + "_" + (filename != null ? filename : "page_" + page);

        // Determine MIME type
        String mimeType = null;
        if (filename != null) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(filename);
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = "image/jpeg";
        }

        // Insert into MediaStore (Pictures/LRReader album)
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/LRReader");

        ContentResolver resolver = mActivity.getContentResolver();
        Uri mediaUri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);

        if (mediaUri == null) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            new File(cacheDir, filename).delete();
            return;
        }

        // Copy temp file to MediaStore URI
        InputStream is = null;
        OutputStream os = null;
        try {
            is = tempFile.openInputStream();
            os = resolver.openOutputStream(mediaUri);
            if (is != null && os != null) {
                IOUtils.copy(is, os);
            }
        } catch (IOException e) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            resolver.delete(mediaUri, null, null);
            return;
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(os);
        }

        // Clean up temp file
        tempFile.delete();

        Toast.makeText(mActivity,
                mActivity.getString(R.string.image_saved, "Pictures/LRReader/" + displayName),
                Toast.LENGTH_SHORT).show();
    }

    // --- Save to user-chosen location (using ActivityResultLauncher) ---

    public void saveImageTo(int page) {
        if (null == mGalleryProvider) {
            return;
        }
        File dir = mActivity.getCacheDir();
        UniFile file;
        if (null == (file = mGalleryProvider.save(page, UniFile.fromFile(dir),
                mGalleryProvider.getImageFilename(page)))) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        String filename = file.getName();
        if (filename == null) {
            Toast.makeText(mActivity, R.string.error_cant_save_image, Toast.LENGTH_SHORT).show();
            return;
        }
        mCacheFileName = filename;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            if (mSaveToLauncher != null) {
                mSaveToLauncher.launch(intent);
            }
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            Toast.makeText(mActivity, R.string.error_cant_find_activity, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle the result from the SAF "save to" picker.
     */
    public void handleSaveToResult(ActivityResult result) {
        if (result == null || result.getResultCode() != Activity.RESULT_OK) {
            return;
        }
        Intent resultData = result.getData();
        if (resultData != null) {
            Uri uri = resultData.getData();
            String filepath = mActivity.getCacheDir() + "/" + mCacheFileName;
            File cacheFile = new File(filepath);

            InputStream is = null;
            OutputStream os = null;
            ContentResolver resolver = mActivity.getContentResolver();

            try {
                is = new FileInputStream(cacheFile);
                os = resolver.openOutputStream(uri);
                IOUtils.copy(is, os);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }

            boolean deleted = cacheFile.delete();
            if (!deleted) {
                cacheFile.deleteOnExit();
            }

            Toast.makeText(mActivity,
                    mActivity.getString(R.string.image_saved, uri.getPath()),
                    Toast.LENGTH_SHORT).show();
            mActivity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
        }
    }

    // --- Page dialog ---

    public void showPageDialog(final int page) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle(mActivity.getResources().getString(R.string.page_menu_title, page + 1));

        final CharSequence[] items = new CharSequence[]{
                mActivity.getString(R.string.page_menu_refresh),
                mActivity.getString(R.string.page_menu_share),
                mActivity.getString(R.string.page_menu_save),
                mActivity.getString(R.string.page_menu_save_to)
        };

        builder.setItems(items, (dialog, which) -> {
            if (mGalleryProvider == null) {
                return;
            }
            switch (which) {
                case 0: // Refresh
                    mGalleryProvider.removeCache(page);
                    mGalleryProvider.forceRequest(page);
                    break;
                case 1: // Share
                    shareImage(page);
                    break;
                case 2: // Save
                    saveImage(page);
                    break;
                case 3: // Save to
                    saveImageTo(page);
                    break;
            }
        });

        AlertDialog dialog = builder.show();
        applyImmersiveToDialog(dialog);
    }

    // --- Utility ---

    /**
     * Prevent edge-to-edge window insets from offsetting dialog content
     * when in fullscreen reading mode.
     */
    public void applyImmersiveToDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null && ReadingSettings.getReadingFullscreen()) {
            View decorView = window.getDecorView();
            ViewCompat.setOnApplyWindowInsetsListener(decorView, (v, insets) ->
                    WindowInsetsCompat.CONSUMED);
            decorView.requestApplyInsets();
        }
    }

    /**
     * Sanitize a string for use as a filename.
     */
    static String sanitizeFilename(String name, int maxLen) {
        if (name == null || name.isEmpty()) return "archive";
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) return "archive";
        if (safe.length() > maxLen) safe = safe.substring(0, maxLen);
        return safe;
    }
}
