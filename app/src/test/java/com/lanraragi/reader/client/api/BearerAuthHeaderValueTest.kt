/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.client.api

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the single LANraragi auth-header wire format so the interceptor and
 * the connection-test probe can never drift (NET-7 follow-up: the token
 * construction previously lived in three hand-copied places).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BearerAuthHeaderValueTest {

    @Test
    fun buildsBearerWithBase64NoWrapToken() {
        val expected = "Bearer " + Base64.encodeToString(
            "secret-key".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        assertEquals(expected, bearerAuthHeaderValue("secret-key"))
    }

    @Test
    fun encodesMultilineSafeToken_noWrapHasNoNewline() {
        // A long key would wrap under Base64.DEFAULT; NO_WRAP must keep the
        // header a single line so it is a legal HTTP header value.
        val longKey = "x".repeat(120)
        assertEquals(false, bearerAuthHeaderValue(longKey).contains('\n'))
    }
}
