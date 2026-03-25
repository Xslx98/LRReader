/*
 * Copyright 2016 Hippo Seven — STUB (LANraragi: E-Hentai functionality removed)
 */

package com.hippo.ehviewer.client;

import okhttp3.OkHttpClient;

/**
 * EhEngine — STUB. All E-Hentai network methods have been removed.
 * This class is kept as a stub because EhApplication.java calls initialize()
 * and SpiderQueen.java calls getGalleryPage/getGalleryPageApi.
 */
public class EhEngine {

    public static void initialize() {
        // No-op: E-Hentai initialization removed
    }

    /** STUB — returns null */
    public static com.hippo.ehviewer.client.parser.GalleryPageParser.Result getGalleryPage(
            Object task, OkHttpClient client, String url, long gid, String token) {
        return null;
    }

    /** STUB — returns null */
    public static com.hippo.ehviewer.client.parser.GalleryPageApiParser.Result getGalleryPageApi(
            Object task, OkHttpClient client, long gid, int index,
            String pToken, String showKey, String previousPToken) {
        return null;
    }
}
