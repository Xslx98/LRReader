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

package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hippo.lib.glgallery.GalleryProvider;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.unifile.UniFile;

public abstract class GalleryProvider2 extends GalleryProvider {

    // With dot
    public static final String[] SUPPORT_IMAGE_EXTENSIONS = {
            ".jpg", // Joint Photographic Experts Group
            ".jpeg",
            ".png", // Portable Network Graphics
            ".gif", // Graphics Interchange Format
            ".webp"
    };

    /** SharedPreferences name for local reading progress storage. */
    private static final String SP_READING_PROGRESS = "reading_progress";

    /**
     * Save reading progress locally (0-indexed page number) with timestamp.
     * @param gid Gallery identifier (used as SP key)
     * @param page 0-indexed current page
     */
    public static void saveReadingProgress(@NonNull Context ctx, long gid, int page) {
        ctx.getApplicationContext()
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .edit()
                .putInt(String.valueOf(gid), page)
                .putLong(gid + "_ts", System.currentTimeMillis() / 1000L)
                .apply();
    }

    /**
     * Load reading progress from local storage.
     * @return 0-indexed page number, or 0 if not found
     */
    public static int loadReadingProgress(@NonNull Context ctx, long gid) {
        return ctx.getApplicationContext()
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getInt(String.valueOf(gid), 0);
    }

    /**
     * Load the timestamp (epoch seconds) of the last local progress save.
     * @return epoch seconds, or 0 if not found
     */
    public static long loadReadingTimestamp(@NonNull Context ctx, long gid) {
        return ctx.getApplicationContext()
                .getSharedPreferences(SP_READING_PROGRESS, Context.MODE_PRIVATE)
                .getLong(gid + "_ts", 0L);
    }

    /** Optional reference to GalleryView for async page navigation. */
    @Nullable
    private volatile GalleryView mGalleryView;

    public void setGalleryView(@Nullable GalleryView galleryView) {
        mGalleryView = galleryView;
    }

    @Nullable
    public GalleryView getGalleryView() {
        return mGalleryView;
    }

    public int getStartPage() {
        return 0;
    }

    public void putStartPage(int page) {}

    /**
     * @return without extension
     */
    @NonNull
    public abstract String getImageFilename(int index);

    public abstract boolean save(int index, @NonNull UniFile file);

    /**
     * @param filename without extension
     */
    @Nullable
    public abstract UniFile save(int index, @NonNull UniFile dir, @NonNull String filename);
}
