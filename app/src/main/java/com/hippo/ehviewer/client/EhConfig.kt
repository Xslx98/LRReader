/*
 * Copyright 2015 Hippo Seven
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

/**
 * Constants used across the app. Originally held EH cookie configuration;
 * instance fields and uconfig() have been removed since the app now
 * exclusively uses LANraragi as its backend.
 *
 * Retained: category bitmask constants, namespace constants, language
 * constants, image size constants, and path constants.
 */
object EhConfig {

    // ==================== Image Size constants ====================

    const val IMAGE_SIZE_AUTO = "a"
    const val IMAGE_SIZE_780X = "780"

    @Deprecated("Use higher resolution sizes")
    const val IMAGE_SIZE_980X = "980"
    const val IMAGE_SIZE_1280X = "1280"
    const val IMAGE_SIZE_1600X = "1600"
    const val IMAGE_SIZE_2400X = "2400"

    // ==================== Category bitmask constants ====================

    const val MISC = 0x1
    const val DOUJINSHI = 0x2
    const val MANGA = 0x4
    const val ARTIST_CG = 0x8
    const val GAME_CG = 0x10
    const val IMAGE_SET = 0x20
    const val COSPLAY = 0x40
    const val ASIAN_PORN = 0x80
    const val NON_H = 0x100
    const val WESTERN = 0x200
    const val ALL_CATEGORY = 0x3ff

    // ==================== Namespace bitmask constants ====================

    const val NAMESPACES_RECLASS = 0x1
    const val NAMESPACES_LANGUAGE = 0x2
    const val NAMESPACES_PARODY = 0x4
    const val NAMESPACES_CHARACTER = 0x8
    const val NAMESPACES_GROUP = 0x10
    const val NAMESPACES_ARTIST = 0x20
    const val NAMESPACES_MALE = 0x40
    const val NAMESPACES_FEMALE = 0x80

    // ==================== Language constants ====================

    const val JAPANESE_ORIGINAL = "0"
    const val JAPANESE_TRANSLATED = "1024"
    const val JAPANESE_REWRITE = "2048"
    const val ENGLISH_ORIGINAL = "1"
    const val ENGLISH_TRANSLATED = "1025"
    const val ENGLISH_REWRITE = "2049"
    const val CHINESE_ORIGINAL = "10"
    const val CHINESE_TRANSLATED = "1034"
    const val CHINESE_REWRITE = "2058"
    const val DUTCH_ORIGINAL = "20"
    const val DUTCH_TRANSLATED = "1044"
    const val DUTCH_REWRITE = "2068"
    const val FRENCH_ORIGINAL = "30"
    const val FRENCH_TRANSLATED = "1054"
    const val FRENCH_REWRITE = "2078"
    const val GERMAN_ORIGINAL = "40"
    const val GERMAN_TRANSLATED = "1064"
    const val GERMAN_REWRITE = "2088"
    const val HUNGARIAN_ORIGINAL = "50"
    const val HUNGARIAN_TRANSLATED = "1074"
    const val HUNGARIAN_REWRITE = "2098"
    const val ITALIAN_ORIGINAL = "60"
    const val ITALIAN_TRANSLATED = "1084"
    const val ITALIAN_REWRITE = "2108"
    const val KOREAN_ORIGINAL = "70"
    const val KOREAN_TRANSLATED = "1094"
    const val KOREAN_REWRITE = "2118"
    const val POLISH_ORIGINAL = "80"
    const val POLISH_TRANSLATED = "1104"
    const val POLISH_REWRITE = "2128"
    const val PORTUGUESE_ORIGINAL = "90"
    const val PORTUGUESE_TRANSLATED = "1114"
    const val PORTUGUESE_REWRITE = "2138"
    const val RUSSIAN_ORIGINAL = "100"
    const val RUSSIAN_TRANSLATED = "1124"
    const val RUSSIAN_REWRITE = "2148"
    const val SPANISH_ORIGINAL = "110"
    const val SPANISH_TRANSLATED = "1134"
    const val SPANISH_REWRITE = "2158"
    const val THAI_ORIGINAL = "120"
    const val THAI_TRANSLATED = "1144"
    const val THAI_REWRITE = "2168"
    const val VIETNAMESE_ORIGINAL = "130"
    const val VIETNAMESE_TRANSLATED = "1154"
    const val VIETNAMESE_REWRITE = "2178"
    const val NA_ORIGINAL = "254"
    const val NA_TRANSLATED = "1278"
    const val NA_REWRITE = "2302"
    const val OTHER_ORIGINAL = "255"
    const val OTHER_TRANSLATED = "1279"
    const val OTHER_REWRITE = "2303"

    // ==================== Path constants ====================

    const val UPDATE_PATH = "LRReader/Update/"
    const val ARCHIVER_PATH = "LRReader/Archiver/"
    const val TORRENT_PATH = "LRReader/Torrent/"

    // ==================== Favorites sort constants ====================

    const val ORDER_BY_FAV_TIME = "f"
    const val ORDER_BY_PUB_TIME = "p"
}
