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

package com.hippo.drawable;

import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

class BitmapPool {

    private static final String TAG = BitmapPool.class.getSimpleName();

    private final Map<Long, ArrayDeque<WeakReference<Bitmap>>> mPool = new HashMap<>();

    private static long sizeKey(int width, int height) {
        return ((long) width << 32) | (height & 0xFFFFFFFFL);
    }

    public synchronized void put(@Nullable Bitmap bitmap) {
        if (bitmap != null) {
            long key = sizeKey(bitmap.getWidth(), bitmap.getHeight());
            ArrayDeque<WeakReference<Bitmap>> deque = mPool.get(key);
            if (deque == null) {
                deque = new ArrayDeque<>();
                mPool.put(key, deque);
            }
            deque.add(new WeakReference<>(bitmap));
        }
    }

    @Nullable
    public synchronized Bitmap get(int width, int height) {
        long key = sizeKey(width, height);
        ArrayDeque<WeakReference<Bitmap>> deque = mPool.get(key);
        if (deque != null) {
            while (!deque.isEmpty()) {
                WeakReference<Bitmap> ref = deque.poll();
                Bitmap bitmap = ref.get();
                if (bitmap != null) {
                    if (deque.isEmpty()) {
                        mPool.remove(key);
                    }
                    return bitmap;
                }
            }
            mPool.remove(key);
        }

        // Can not find reusable bitmap
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory");
            return null;
        }
    }
}
