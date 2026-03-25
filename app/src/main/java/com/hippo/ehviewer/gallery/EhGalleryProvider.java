/*
 * STUB (LANraragi: E-Hentai gallery provider removed)
 * LANraragi uses LRRGalleryProvider instead.
 */
package com.hippo.ehviewer.gallery;

import android.content.Context;
import com.hippo.ehviewer.client.data.GalleryInfo;

/**
 * EhGalleryProvider — STUB.
 * E-Hentai SpiderQueen-based gallery provider has been removed.
 */
public class EhGalleryProvider extends GalleryProvider2 {

    public EhGalleryProvider(Context context, GalleryInfo galleryInfo) {
        // No-op: E-Hentai gallery provider removed
    }

    @Override
    public int size() { return 0; }

    @Override
    protected void onRequest(int index) {}

    @Override
    protected void onForceRequest(int index) {}

    @Override
    protected void onCancelRequest(int index) {}

    @Override
    public String getError() { return "E-Hentai provider removed"; }

    @Override
    public String getImageFilename(int index) { return ""; }

    @Override
    public boolean save(int index, com.hippo.unifile.UniFile file) { return false; }

    @Override
    public com.hippo.unifile.UniFile save(int index, com.hippo.unifile.UniFile dir, String filename) { return null; }
}
