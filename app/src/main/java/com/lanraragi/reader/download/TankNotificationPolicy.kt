/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.download

/**
 * When does a downloaded tankoubon count as "done" for the completion
 * notification? Once for the whole tank: every member terminal
 * (FINISH → DONE; any FAILED with nothing still running → FAILED). A
 * member still queued / downloading, or one the user stopped / that has
 * no row, keeps the tank PENDING and no notification fires yet.
 */
object TankNotificationPolicy {

    enum class Outcome { PENDING, DONE, FAILED }

    fun completion(memberStates: List<DownloadState>): Outcome {
        if (memberStates.isEmpty()) return Outcome.PENDING
        var failed = false
        for (state in memberStates) {
            when (state) {
                DownloadState.FINISH -> Unit
                DownloadState.FAILED -> failed = true
                DownloadState.WAIT, DownloadState.DOWNLOAD,
                DownloadState.NONE, DownloadState.INVALID -> return Outcome.PENDING
            }
        }
        return if (failed) Outcome.FAILED else Outcome.DONE
    }
}
