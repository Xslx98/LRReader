package com.hippo

object Native {
    @JvmStatic
    fun initialize() {
        System.loadLibrary("ehviewer")
    }
}