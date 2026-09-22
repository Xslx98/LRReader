package com.lanraragi.reader

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * [MockWebServer.takeRequest] with a deadline: the no-arg overload blocks
 * forever when a regression skips the request, hanging the whole suite
 * instead of failing the one test.
 */
fun MockWebServer.awaitRequest(timeoutSeconds: Long = 10): RecordedRequest =
    takeRequest(timeoutSeconds, TimeUnit.SECONDS)
        ?: throw AssertionError("no request reached MockWebServer within ${timeoutSeconds}s")
