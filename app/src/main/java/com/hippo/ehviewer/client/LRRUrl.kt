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

import com.hippo.ehviewer.settings.AppearanceSettings
import com.hippo.network.UrlBuilder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * appurl请求设置
 */
object LRRUrl {

    const val SITE_E = 0
    const val SITE_EX = 1

    const val DOMAIN_EX = "exhentai.org"
    const val DOMAIN_E = "e-hentai.org"
    const val DOMAIN_LOFI = "lofi.e-hentai.org"

    const val REFERER_EX = "https://$DOMAIN_EX"
    const val REFERER_E = "https://$DOMAIN_E"

    const val HOST_EX = "$REFERER_EX/"
    const val HOST_E = "$REFERER_E/"

    const val API_SIGN_IN = "https://forums.e-hentai.org/index.php?act=Login&CODE=01"

    const val URL_NEWS_E = "${HOST_E}news.php"

    const val API_E = "${HOST_E}api.php"
    const val API_EX = "${HOST_EX}api.php"

    const val HOME_E = "${HOST_E}home.php"
    const val HOME_EX = "${HOST_EX}home.php"

    const val URL_POPULAR_E = "https://e-hentai.org/popular"
    const val URL_POPULAR_EX = "https://exhentai.org/popular"

    const val URL_TOP_LIST_E = "${HOST_E}toplist.php"
    const val URL_TOP_LIST_EX = "${HOST_EX}toplist.php"

    const val URL_IMAGE_SEARCH_E = "https://upld.e-hentai.org/image_lookup.php"
    const val URL_IMAGE_SEARCH_EX = "https://upld.exhentai.org/upld/image_lookup.php"

    const val URL_SIGN_IN = "https://forums.e-hentai.org/index.php?act=Login"
    const val URL_REGISTER = "https://forums.e-hentai.org/index.php?act=Reg&CODE=00"
    const val URL_FAVORITES_E = "${HOST_E}favorites.php"
    const val URL_FAVORITES_EX = "${HOST_EX}favorites.php"
    const val DOMAIN_FORUMS = "forums.e-hentai.org"
    const val URL_FORUMS = "https://forums.e-hentai.org/"

    const val ORIGIN_EX = REFERER_EX
    const val ORIGIN_E = REFERER_E

    const val URL_UCONFIG_E = "${HOST_E}uconfig.php"
    const val URL_UCONFIG_EX = "${HOST_EX}uconfig.php"

    const val URL_MY_TAGS_E = "${HOST_E}mytags"
    const val URL_MY_TAGS_EX = "${HOST_EX}mytags"

    const val URL_WATCHED_E = "${HOST_E}watched"
    const val URL_WATCHED_EX = "${HOST_EX}watched"

    private const val URL_PREFIX_THUMB_E = "https://ehgt.org/"
    private const val URL_PREFIX_THUMB_EX = "https://exhentai.org/t/"

    @JvmStatic
    fun getGalleryDetailUrl(gid: Long, token: String?): String {
        return getGalleryDetailUrl(gid, token, 0, false)
    }

    @JvmStatic
    fun getHost(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> HOST_EX
            else -> HOST_E
        }
    }

    @JvmStatic
    fun getHomeUrl(): String = HOME_E

    @JvmStatic
    fun getMyTag(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_MY_TAGS_EX
            else -> URL_MY_TAGS_E
        }
    }

    @JvmStatic
    fun getFavoritesUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_FAVORITES_EX
            else -> URL_FAVORITES_E
        }
    }

    @JvmStatic
    fun getApiUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> API_EX
            else -> API_E
        }
    }

    @JvmStatic
    fun getReferer(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> REFERER_EX
            else -> REFERER_E
        }
    }

    @JvmStatic
    fun getOrigin(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> ORIGIN_EX
            else -> ORIGIN_E
        }
    }

    @JvmStatic
    fun getUConfigUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_UCONFIG_EX
            else -> URL_UCONFIG_E
        }
    }

    @JvmStatic
    fun getMyTagsUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_MY_TAGS_EX
            else -> URL_MY_TAGS_E
        }
    }

    /**
     * 获取画廊详情地址
     */
    @JvmStatic
    fun getGalleryDetailUrl(gid: Long, token: String?, index: Int, allComment: Boolean): String {
        val builder = UrlBuilder(getHost() + "g/" + gid + '/' + (token ?: "") + '/')
        if (index != 0) {
            builder.addQuery("p", index)
        }
        if (allComment) {
            builder.addQuery("hc", 1)
        }
        return builder.build()
    }

    @JvmStatic
    fun getPageUrl(gid: Long, index: Int, pToken: String?): String {
        return getHost() + "s/" + pToken + '/' + gid + '-' + (index + 1)
    }

    @JvmStatic
    fun getAddFavorites(gid: Long, token: String?): String {
        return getHost() + "gallerypopups.php?gid=" + gid + "&t=" + token + "&act=addfav"
    }

    @JvmStatic
    fun getDownloadArchive(gid: Long, token: String?, or: String): String {
        return if (or.isEmpty()) {
            getHost() + "archiver.php?gid=" + gid + "&token=" + token
        } else {
            getHost() + "archiver.php?gid=" + gid + "&token=" + token + "&or=" + or
        }
    }

    @JvmStatic
    fun getTagDefinitionUrl(tag: String?): String {
        return "https://ehwiki.org/wiki/" + (tag?.replace(' ', '_') ?: "")
    }

    /**
     * 获取'favorites'连接
     */
    @JvmStatic
    fun getPopularUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_POPULAR_EX
            else -> URL_POPULAR_E
        }
    }

    /**
     * 获取排行榜'top list'连接
     */
    @JvmStatic
    fun getTopListUrl(): String = URL_TOP_LIST_E

    /**
     * 获取排行榜'top list'连接
     */
    @JvmStatic
    fun getEhNewsUrl(): String = URL_NEWS_E

    @JvmStatic
    fun getImageSearchUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_IMAGE_SEARCH_EX
            else -> URL_IMAGE_SEARCH_E
        }
    }

    @JvmStatic
    fun getWatchedUrl(): String {
        return when (AppearanceSettings.getGallerySite()) {
            SITE_EX -> URL_WATCHED_EX
            else -> URL_WATCHED_E
        }
    }

    @JvmStatic
    fun getThumbUrlPrefix(): String {
        return URL_PREFIX_THUMB_E
    }

    @JvmStatic
    fun getFixedPreviewThumbUrl(originUrl: String): String {
        val url = originUrl.toHttpUrlOrNull() ?: return originUrl
        val pathSegments = url.pathSegments
        if (pathSegments.size < 3) return originUrl

        val iterator = pathSegments.listIterator(pathSegments.size)
        // The last segments, like
        // 317a1a254cd9c3269e71b2aa2671fe8d28c91097-260198-640-480-png_250.jpg
        if (!iterator.hasPrevious()) return originUrl
        val lastSegment = iterator.previous()
        // The second last segments, like
        // 7a
        if (!iterator.hasPrevious()) return originUrl
        val secondLastSegment = iterator.previous()
        // The third last segments, like
        // 31
        if (!iterator.hasPrevious()) return originUrl
        val thirdLastSegment = iterator.previous()
        // Check path segments
        return if (lastSegment.startsWith(thirdLastSegment) &&
            lastSegment.startsWith(secondLastSegment, thirdLastSegment.length)
        ) {
            getThumbUrlPrefix() + thirdLastSegment + "/" + secondLastSegment + "/" + lastSegment
        } else {
            originUrl
        }
    }
}
