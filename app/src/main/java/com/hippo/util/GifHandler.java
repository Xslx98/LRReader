package com.hippo.util;

import android.graphics.Bitmap;
import java.io.Closeable;

public class GifHandler implements Closeable {

    private long gifAddr;

    public GifHandler(String path) {
        this.gifAddr = loadPath(path);
    }

    static {
        System.loadLibrary("ehviewer");
    }

    private native long loadPath(String path);
    public native int getWidth(long ndkGif);
    public native int getHeight(long ndkGif);
    public native int updateFrame(long ndkGif, Bitmap bitmap);
    private native void destroy(long ndkGif);

    public int getWidth() {
        return getWidth(gifAddr);
    }
    public int getHeight() {
        return getHeight(gifAddr);
    }

    public int updateFrame(Bitmap bitmap) {
        if (gifAddr == 0) return 0;
        return updateFrame(gifAddr, bitmap);
    }

    @Override
    public void close() {
        if (gifAddr != 0) {
            destroy(gifAddr);
            gifAddr = 0;
        }
    }
}
