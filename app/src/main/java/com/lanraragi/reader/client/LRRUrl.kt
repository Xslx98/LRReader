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

package com.lanraragi.reader.client

import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.framework.network.UrlBuilder

/**
 * appurl请求设置
 */
object LRRUrl {

    const val SITE_EX = 1

    private const val HOST_EX = "https://exhentai.org/"
    private const val HOST_E = "https://e-hentai.org/"

    @JvmStatic
    fun getHost(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> HOST_EX
            else -> HOST_E
        }
    }

    /**
     * 获取画廊详情地址
     */
    @JvmStatic
    fun getGalleryDetailUrl(gid: Long, arcid: String?, index: Int, allComment: Boolean): String {
        val builder = UrlBuilder(getHost() + "g/" + gid + '/' + (arcid ?: "") + '/')
        if (index != 0) {
            builder.addQuery("p", index)
        }
        if (allComment) {
            builder.addQuery("hc", 1)
        }
        return builder.build()
    }

    @JvmStatic
    fun getTagDefinitionUrl(tag: String?): String {
        return "https://ehwiki.org/wiki/" + (tag?.replace(' ', '_') ?: "")
    }
}
