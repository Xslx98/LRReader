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

package com.hippo.conaco;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.streampipe.InputStreamPipe;

public interface ValueHelper<V> {

    /**
     * Decode object for the {@code InputStreamPipe}
     *
     * @param isPipe the {@code InputStreamPipe}
     * @return the decoded object
     */
    @Nullable
    V decode(@NonNull InputStreamPipe isPipe);

    @Nullable
    V decode(@NonNull InputStreamPipe isPipe, boolean hardware);

    /**
     * Decode with a target-size hint (pixels); the decoded value should keep
     * both dimensions at or above the target. Non-positive targets mean no
     * hint. Default implementation ignores the hint so legacy helpers keep
     * their behavior.
     */
    @Nullable
    default V decode(@NonNull InputStreamPipe isPipe, boolean hardware,
            int targetWidth, int targetHeight) {
        return decode(isPipe, hardware);
    }

    /**
     * Get the size of the object
     */
    int sizeOf(@NonNull String key, @NonNull V value);

    /**
     * Called when the object added to memory cache
     */
    void onAddToMemoryCache(@NonNull V oldValue);

    /**
     * Called when the object removed from memory cache
     */
    void onRemoveFromMemoryCache(@NonNull String key, @NonNull V oldValue);

    /**
     * Use cache memory or not. Sometimes large object
     * should not be stored in memory cache.
     */
    boolean useMemoryCache(@NonNull String key, V holder);
}
