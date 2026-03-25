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

package com.hippo.ehviewer.client;

/**
 * Constants used across the app. Originally held EH cookie configuration;
 * instance fields and uconfig() have been removed since the app now
 * exclusively uses LANraragi as its backend.
 *
 * Retained: category bitmask constants, namespace constants, language
 * constants, image size constants, path constants, and cookie keys
 * still referenced by EhCookieStore.
 */
public class EhConfig {

    // ==================== Cookie keys (used by EhCookieStore) ====================

    /**
     * The Cookie key of uconfig
     */
    public static final String KEY_UCONFIG = "uconfig";

    /**
     * The Cookie key of lofi resolution
     */
    public static final String KEY_LOFI_RESOLUTION = "xres";

    /**
     * The Cookie key of show warning
     */
    public static final String KEY_CONTENT_WARNING = "nw";

    // ==================== Image Size constants ====================

    public static final String IMAGE_SIZE_AUTO = "a";
    public static final String IMAGE_SIZE_780X = "780";
    @Deprecated
    public static final String IMAGE_SIZE_980X = "980";
    public static final String IMAGE_SIZE_1280X = "1280";
    public static final String IMAGE_SIZE_1600X = "1600";
    public static final String IMAGE_SIZE_2400X = "2400";

    // ==================== Category bitmask constants ====================

    public static final int MISC = 0x1;
    public static final int DOUJINSHI = 0x2;
    public static final int MANGA = 0x4;
    public static final int ARTIST_CG = 0x8;
    public static final int GAME_CG = 0x10;
    public static final int IMAGE_SET = 0x20;
    public static final int COSPLAY = 0x40;
    public static final int ASIAN_PORN = 0x80;
    public static final int NON_H = 0x100;
    public static final int WESTERN = 0x200;
    public static final int ALL_CATEGORY = 0x3ff;

    // ==================== Namespace bitmask constants ====================

    public static final int NAMESPACES_RECLASS = 0x1;
    public static final int NAMESPACES_LANGUAGE = 0x2;
    public static final int NAMESPACES_PARODY = 0x4;
    public static final int NAMESPACES_CHARACTER = 0x8;
    public static final int NAMESPACES_GROUP = 0x10;
    public static final int NAMESPACES_ARTIST = 0x20;
    public static final int NAMESPACES_MALE = 0x40;
    public static final int NAMESPACES_FEMALE = 0x80;

    // ==================== Language constants ====================

    public static final String JAPANESE_ORIGINAL = "0";
    public static final String JAPANESE_TRANSLATED = "1024";
    public static final String JAPANESE_REWRITE = "2048";
    public static final String ENGLISH_ORIGINAL = "1";
    public static final String ENGLISH_TRANSLATED = "1025";
    public static final String ENGLISH_REWRITE = "2049";
    public static final String CHINESE_ORIGINAL = "10";
    public static final String CHINESE_TRANSLATED = "1034";
    public static final String CHINESE_REWRITE = "2058";
    public static final String DUTCH_ORIGINAL = "20";
    public static final String DUTCH_TRANSLATED = "1044";
    public static final String DUTCH_REWRITE = "2068";
    public static final String FRENCH_ORIGINAL = "30";
    public static final String FRENCH_TRANSLATED = "1054";
    public static final String FRENCH_REWRITE = "2078";
    public static final String GERMAN_ORIGINAL = "40";
    public static final String GERMAN_TRANSLATED = "1064";
    public static final String GERMAN_REWRITE = "2088";
    public static final String HUNGARIAN_ORIGINAL = "50";
    public static final String HUNGARIAN_TRANSLATED = "1074";
    public static final String HUNGARIAN_REWRITE = "2098";
    public static final String ITALIAN_ORIGINAL = "60";
    public static final String ITALIAN_TRANSLATED = "1084";
    public static final String ITALIAN_REWRITE = "2108";
    public static final String KOREAN_ORIGINAL = "70";
    public static final String KOREAN_TRANSLATED = "1094";
    public static final String KOREAN_REWRITE = "2118";
    public static final String POLISH_ORIGINAL = "80";
    public static final String POLISH_TRANSLATED = "1104";
    public static final String POLISH_REWRITE = "2128";
    public static final String PORTUGUESE_ORIGINAL = "90";
    public static final String PORTUGUESE_TRANSLATED = "1114";
    public static final String PORTUGUESE_REWRITE = "2138";
    public static final String RUSSIAN_ORIGINAL = "100";
    public static final String RUSSIAN_TRANSLATED = "1124";
    public static final String RUSSIAN_REWRITE = "2148";
    public static final String SPANISH_ORIGINAL = "110";
    public static final String SPANISH_TRANSLATED = "1134";
    public static final String SPANISH_REWRITE = "2158";
    public static final String THAI_ORIGINAL = "120";
    public static final String THAI_TRANSLATED = "1144";
    public static final String THAI_REWRITE = "2168";
    public static final String VIETNAMESE_ORIGINAL = "130";
    public static final String VIETNAMESE_TRANSLATED = "1154";
    public static final String VIETNAMESE_REWRITE = "2178";
    public static final String NA_ORIGINAL = "254";
    public static final String NA_TRANSLATED = "1278";
    public static final String NA_REWRITE = "2302";
    public static final String OTHER_ORIGINAL = "255";
    public static final String OTHER_TRANSLATED = "1279";
    public static final String OTHER_REWRITE = "2303";

    // ==================== Content Warning constants ====================

    public static final String CONTENT_WARNING_SHOW = "0";
    public static final String CONTENT_WARNING_NOT_SHOW = "1";

    // ==================== Path constants ====================

    public static final String UPDATE_PATH = "LRReader/Update/";
    public static final String ARCHIVER_PATH = "LRReader/Archiver/";
    public static final String TORRENT_PATH = "LRReader/Torrent/";

    // ==================== Favorites sort constants ====================

    public static final String ORDER_BY_FAV_TIME = "f";
    public static final String ORDER_BY_PUB_TIME = "p";

    private EhConfig() {
        // Utility class — no instances needed
    }
}
