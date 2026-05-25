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

package com.hippo.ehviewer.client

object LRRCacheKeyFactory {

    @JvmStatic
    fun getThumbKey(arcid: String): String =
        "preview:large:$arcid:0"

    @JvmStatic
    fun getImageKey(arcid: String, index: Int): String =
        "image:$arcid:$index"

    /**
     * Cache key for the per-page thumbnail used by the detail-page
     * preview grid. Distinct namespace (`preview:page:`) from the
     * cover thumbnail key (`preview:large:`) so the two never collide.
     *
     * @param page 0-indexed page number.
     */
    @JvmStatic
    fun getPageThumbKey(arcid: String, page: Int): String =
        "preview:page:$arcid:$page"
}
