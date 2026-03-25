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

package com.hippo.lib.glview.glrenderer;

import android.opengl.GLES20;
import android.util.Log;

import javax.microedition.khronos.opengles.GL11;

public abstract class NativeTexture extends BasicTexture {

    private static final String TAG = NativeTexture.class.getSimpleName();

    private boolean mContentValid = true;

    private boolean mOpaque = true;

    public void invalidateContent() {
        mContentValid = false;
    }

    public static void checkError() {
        int error = GLES20.glGetError();
        if (error != 0) {
            Throwable t = new Throwable();
            Log.e(TAG, "GL error: " + error, t);
        }
    }

    protected abstract void texImage(boolean init);

    private void uploadToCanvas(GLCanvas canvas) {
        // Get id
        mId = canvas.getGLId().generateTexture();

        // Prepare
        canvas.setTextureParameters(this);

        // Call glTexImage2D
        GLES20.glBindTexture(getTarget(), mId);
        checkError();
        texImage(true);

        // Only mark as loaded if texImage succeeded (no GL error)
        int error = GLES20.glGetError();
        if (error == 0) {
            setAssociatedCanvas(canvas);
            mState = STATE_LOADED;
            mContentValid = true;
        } else {
            Log.e(TAG, "texImage failed with GL error: " + error + ", will retry");
            // Delete the texture so it can be re-created on next attempt
            GLES20.glDeleteTextures(1, new int[]{mId}, 0);
            mId = 0;
        }
    }

    public void updateContent(GLCanvas canvas) {
        if (!isLoaded()) {
            uploadToCanvas(canvas);
        } else if (!mContentValid) {
            // Call glTexSubImage2D
            GLES20.glBindTexture(getTarget(), mId);
            checkError();
            texImage(false);
            // Only mark valid if no GL error
            int error = GLES20.glGetError();
            if (error == 0) {
                mContentValid = true;
            } else {
                Log.e(TAG, "texSubImage failed with GL error: " + error);
            }
        }
    }

    /**
     * Whether the content on GPU is valid.
     */
    public boolean isContentValid() {
        return isLoaded() && mContentValid;
    }

    @Override
    protected boolean onBind(GLCanvas canvas) {
        updateContent(canvas);
        return isContentValid();
    }

    @Override
    protected int getTarget() {
        return GL11.GL_TEXTURE_2D;
    }

    public void setOpaque(boolean isOpaque) {
        mOpaque = isOpaque;
    }

    @Override
    public boolean isOpaque() {
        return mOpaque;
    }
}
