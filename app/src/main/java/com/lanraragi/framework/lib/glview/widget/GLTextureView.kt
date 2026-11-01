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

package com.lanraragi.framework.lib.glview.widget

import android.graphics.RectF
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.Texture
import com.lanraragi.framework.lib.glview.view.GLView

class GLTextureView : GLView() {

    private val src = RectF()
    private val dst = RectF()

    var texture: Texture? = null
        set(texture) {
            field = texture
            if (texture != null) {
                src.set(0.0f, 0.0f, texture.getWidth().toFloat(), texture.getHeight().toFloat())
            } else {
                src.setEmpty()
            }
            invalidate()
        }

    override fun getSuggestedMinimumWidth(): Int {
        val texture = this.texture
        return if (texture == null) super.getSuggestedMinimumWidth() else texture.getWidth() + mPaddings.width()
    }

    // Falls back to the minimum width without a texture, as it always has.
    override fun getSuggestedMinimumHeight(): Int {
        val texture = this.texture
        return if (texture == null) super.getSuggestedMinimumWidth() else texture.getHeight() + mPaddings.height()
    }

    override fun onRender(canvas: GLCanvas) {
        val texture = this.texture
        if (texture == null || src.isEmpty) {
            return
        }

        dst.set(
            mPaddings.left.toFloat(),
            mPaddings.top.toFloat(),
            (width - mPaddings.right).toFloat(),
            (height - mPaddings.bottom).toFloat()
        )
        if (dst.isEmpty) {
            return
        }

        texture.draw(canvas, src, dst)
    }
}
