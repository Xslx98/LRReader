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

import com.hippo.okhttp.ChromeRequestBuilder

class EhRequestBuilder : ChromeRequestBuilder {

    constructor(url: String) : this(url, null, null)

    constructor(url: String, referer: String?) : this(url, referer, null)

    constructor(url: String, referer: String?, origin: String?) : super(url) {
        if (referer != null) {
            addHeader("Referer", referer)
        }
        if (origin != null) {
            addHeader("Origin", origin)
        }
    }

    constructor(headers: Map<String, String>, url: String) : super(url) {
        for ((key, value) in headers) {
            if (value != null) {
                // OkHttp 不允许 Header 中包含换行等控制字符，这里做一次清理，避免 0x0a 崩溃
                val cleanValue = value.replace("\r", "").replace("\n", "")
                addHeader(key, cleanValue)
            }
        }
    }
}
