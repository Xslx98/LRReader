package com.lanraragi.framework.lib.glview.glrenderer

import android.opengl.GLES20
import javax.microedition.khronos.opengles.GL11
import javax.microedition.khronos.opengles.GL11ExtensionPack

class GLES20IdImpl : GLId {
    private val tempIntArray = IntArray(1)

    override fun generateTexture(): Int {
        GLES20.glGenTextures(1, tempIntArray, 0)
        GLES20Canvas.checkError()
        return tempIntArray[0]
    }

    override fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) {
        GLES20.glGenBuffers(n, buffers, offset)
        GLES20Canvas.checkError()
    }

    override fun glDeleteTextures(gl: GL11?, n: Int, textures: IntArray, offset: Int) {
        GLES20.glDeleteTextures(n, textures, offset)
        GLES20Canvas.checkError()
    }

    override fun glDeleteBuffers(gl: GL11?, n: Int, buffers: IntArray, offset: Int) {
        GLES20.glDeleteBuffers(n, buffers, offset)
        GLES20Canvas.checkError()
    }

    override fun glDeleteFramebuffers(gl11ep: GL11ExtensionPack?, n: Int, buffers: IntArray, offset: Int) {
        GLES20.glDeleteFramebuffers(n, buffers, offset)
        GLES20Canvas.checkError()
    }
}
