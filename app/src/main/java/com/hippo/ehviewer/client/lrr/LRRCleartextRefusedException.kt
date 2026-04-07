package com.hippo.ehviewer.client.lrr

import java.io.IOException

/**
 * Thrown by [LRRCleartextRejectionInterceptor] when an HTTP (cleartext) request
 * is rejected by the app-layer policy.
 *
 * Subclass of [IOException] so OkHttp surfaces it as a normal network failure
 * — existing `try/catch (IOException)` paths handle it without crashing.
 *
 * Reasons a request is refused:
 *   1. No active server profile is configured (cleartext disallowed by default).
 *   2. Request host:port does not match the active profile's host:port.
 *   3. Request scheme does not match the active profile's scheme (downgrade).
 *   4. Active profile has `allowCleartext == false`.
 */
class LRRCleartextRefusedException(message: String) : IOException(message)
