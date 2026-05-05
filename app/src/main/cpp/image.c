#include <stdlib.h>
#include <string.h>

#include <android/bitmap.h>
#include <android/data_space.h>
#include <android/log.h>

#include <GLES2/gl2.h>
#include <jni.h>

#define TAG "ImageDecoder_wrapper"

#include "ehviewer.h"

#define IMAGE_TILE_MAX_SIZE (512 * 512)

// Thread safety: tile_buffer is accessed exclusively from the GL render thread
// via NativeTexture.updateContent() -> ImageWrapper.texImage() -> Image.texImage() -> nativeTexImage().
// OpenGL ES spec mandates single-thread-per-context, so no mutex needed.
static char tile_buffer[IMAGE_TILE_MAX_SIZE * 4];

// Replicate image edge pixels into the BORDER region around them so GL_LINEAR
// sampling at the image outer edges blends real pixels instead of (0,0,0,0)
// from the memset above. Without this, the bottom/top row of every page is
// rendered as half-alpha and bleeds the GL clear colour through, producing
// the dark hairline seam that users see between vertically stacked pages.
static void clampEdgesIntoBorder(char *buf, int buf_w, int buf_h,
                                  int cx0, int cy0, int cx1, int cy1) {
    if (cx0 >= cx1 || cy0 >= cy1) return;
    if (cx0 <= 0 && cy0 <= 0 && cx1 >= buf_w && cy1 >= buf_h) return;
    int row_stride = buf_w * 4;
    int span_bytes = (cx1 - cx0) * 4;
    // Vertical extension: rows above/below content copy the nearest content row
    if (cy0 > 0) {
        char *src_row = buf + cy0 * row_stride + cx0 * 4;
        for (int y = 0; y < cy0; y++) {
            memcpy(buf + y * row_stride + cx0 * 4, src_row, (size_t) span_bytes);
        }
    }
    if (cy1 < buf_h) {
        char *src_row = buf + (cy1 - 1) * row_stride + cx0 * 4;
        for (int y = cy1; y < buf_h; y++) {
            memcpy(buf + y * row_stride + cx0 * 4, src_row, (size_t) span_bytes);
        }
    }
    // Horizontal extension: every row's leftmost / rightmost border pixels
    // copy the nearest in-content pixel of that row (covers corners too).
    for (int y = 0; y < buf_h; y++) {
        char *row = buf + y * row_stride;
        if (cx0 > 0) {
            char *first = row + cx0 * 4;
            for (int x = 0; x < cx0; x++) memcpy(row + x * 4, first, 4);
        }
        if (cx1 < buf_w) {
            char *last = row + (cx1 - 1) * 4;
            for (int x = cx1; x < buf_w; x++) memcpy(row + x * 4, last, 4);
        }
    }
}

bool copyPixels(const void *src, int src_w, int src_h, int src_x, int src_y,
                 void *dst, int dst_w, int dst_h, int dst_x, int dst_y,
                 int width, int height) {
    int left;
    int line;
    size_t line_stride;
    int src_stride;
    int src_pos;
    int dst_pos;
    size_t dst_blank_length;

    // Sanitize
    if (src_x < 0) {
        width -= src_x;
        dst_x -= src_x;
        src_x = 0;
    }
    if (dst_x < 0) {
        width -= dst_x;
        src_x -= dst_x;
        dst_x = 0;
    }
    if (width <= 0) {
        return false;
    }
    if (src_y < 0) {
        height -= src_y;
        dst_y -= src_y;
        src_y = 0;
    }
    if (dst_y < 0) {
        height -= dst_y;
        src_y -= dst_y;
        dst_y = 0;
    }
    if (height <= 0) {
        return false;
    }
    left = src_x + width - src_w;
    if (left > 0) {
        width -= left;
    }
    left = dst_x + width - dst_w;
    if (left > 0) {
        width -= left;
    }
    if (width <= 0) {
        return false;
    }
    left = src_y + height - src_h;
    if (left > 0) {
        height -= left;
    }
    left = dst_y + height - dst_h;
    if (left > 0) {
        height -= left;
    }
    if (height <= 0) {
        return false;
    }

    // Init
    line_stride = (size_t) (width * 4);
    src_stride = src_w * 4;
    src_pos = src_y * src_stride + src_x * 4;
    dst_pos = 0;

    dst_blank_length = (size_t) (dst_y * dst_w + dst_x) * 4;

    // First line
    dst_pos += (int) dst_blank_length;
    memcpy(dst + dst_pos, src + src_pos, line_stride);
    dst_pos += (int) line_stride;
    src_pos += src_stride;

    // Other lines
    dst_blank_length = (size_t) ((dst_w - width) * 4);
    for (line = 1; line < height; line++) {
        dst_pos += (int) dst_blank_length;
        memcpy(dst + dst_pos, src + src_pos, line_stride);
        dst_pos += (int) line_stride;
        src_pos += src_stride;
    }

    return true;
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_ImageKt_nativeTexImage(JNIEnv *env, jclass clazz, jobject bitmap, jboolean init,
                                          jint offset_x, jint offset_y, jint width, jint height) {
    if (width * height > IMAGE_TILE_MAX_SIZE)
        return;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    AndroidBitmap_getInfo(env, bitmap, &info);
    // Zero tile buffer to prevent stale data in border/uncovered regions
    memset(tile_buffer, 0, (size_t)(width * height * 4));
    copyPixels(pixels, info.width, info.height, offset_x, offset_y, tile_buffer, width, height, 0, 0, width, height);
    AndroidBitmap_unlockPixels(env, bitmap);
    // Compute actual content rect inside tile_buffer and clamp edge pixels
    // outwards so GL_LINEAR border sampling stays opaque (no seam line).
    int cx0 = offset_x < 0 ? -offset_x : 0;
    int cy0 = offset_y < 0 ? -offset_y : 0;
    int cx1 = (int) info.width - offset_x;
    int cy1 = (int) info.height - offset_y;
    if (cx1 > width) cx1 = width;
    if (cy1 > height) cy1 = height;
    if (cx0 > width) cx0 = width;
    if (cy0 > height) cy0 = height;
    clampEdgesIntoBorder(tile_buffer, width, height, cx0, cy0, cx1, cy1);
    if (init) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     tile_buffer);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE,
                        tile_buffer);
    }
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_Image_nativeRender(JNIEnv *env, jclass clazz, jobject srcBitmap, jint src_x,
                                            jint src_y, jobject dst, jint dst_x, jint dst_y, jint width,
                                            jint height) {
    AndroidBitmapInfo dstInfo;
    AndroidBitmapInfo srcInfo;
    void *srcPixels = NULL;
    void *dstPixels = NULL;

    AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels);
    AndroidBitmap_lockPixels(env, dst, &dstPixels);
    AndroidBitmap_getInfo(env, srcBitmap, &srcInfo);
    AndroidBitmap_getInfo(env, dst, &dstInfo);

    copyPixels(srcPixels, srcInfo.width, srcInfo.height, src_x, src_y, dstPixels, dstInfo.width,
                dstInfo.height, dst_x, dst_y,
                width,
                height);

    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, srcBitmap);
}

JNIEXPORT void JNICALL
Java_com_hippo_lib_image_Image_nativeTexImage(JNIEnv *env, jclass clazz, jobject bitmap, jboolean init,
                                              jint offset_x, jint offset_y, jint width, jint height) {
    if (width * height > IMAGE_TILE_MAX_SIZE)
        return;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    AndroidBitmap_getInfo(env, bitmap, &info);
    // Zero tile buffer to prevent stale data in border/uncovered regions
    memset(tile_buffer, 0, (size_t)(width * height * 4));
    copyPixels(pixels, info.width, info.height, offset_x, offset_y, tile_buffer, width, height, 0, 0, width, height);
    AndroidBitmap_unlockPixels(env, bitmap);
    // Compute actual content rect inside tile_buffer and clamp edge pixels
    // outwards so GL_LINEAR border sampling stays opaque (no seam line).
    int cx0 = offset_x < 0 ? -offset_x : 0;
    int cy0 = offset_y < 0 ? -offset_y : 0;
    int cx1 = (int) info.width - offset_x;
    int cy1 = (int) info.height - offset_y;
    if (cx1 > width) cx1 = width;
    if (cy1 > height) cy1 = height;
    if (cx0 > width) cx0 = width;
    if (cy0 > height) cy0 = height;
    clampEdgesIntoBorder(tile_buffer, width, height, cx0, cy0, cx1, cy1);
    if (init) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     tile_buffer);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE,
                        tile_buffer);
    }
}