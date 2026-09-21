/*
 * Copyright 2026 LR Reader
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui.scene.download.part

import com.lanraragi.reader.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the tank card's control ladder (spec 2026-09-21 §4): a card shows the
 * same start / stop / progress affordances an ordinary row would, with the
 * extra INCOMPLETE case (members missing) lighting the start control.
 */
class DownloadAdapterTankCardControlsTest {

    private fun controls(state: DownloadState, missing: Int = 0) =
        DownloadAdapter.tankCardControls(state, missing)

    @Test
    fun `downloading card shows stop and progress`() {
        for (state in listOf(DownloadState.WAIT, DownloadState.DOWNLOAD)) {
            assertEquals(DownloadAdapter.TankCardControls(start = false, stop = true, progress = true), controls(state))
            assertEquals(DownloadAdapter.TankCardControls(start = false, stop = true, progress = true), controls(state, 2))
        }
    }

    @Test
    fun `failed card shows start`() {
        assertEquals(DownloadAdapter.TankCardControls(start = true, stop = false, progress = false), controls(DownloadState.FAILED))
    }

    @Test
    fun `complete card shows neither`() {
        assertEquals(DownloadAdapter.TankCardControls(start = false, stop = false, progress = false), controls(DownloadState.FINISH))
    }

    @Test
    fun `incomplete or idle card shows start`() {
        assertEquals(DownloadAdapter.TankCardControls(start = true, stop = false, progress = false), controls(DownloadState.FINISH, 1))
        assertEquals(DownloadAdapter.TankCardControls(start = true, stop = false, progress = false), controls(DownloadState.NONE))
        assertEquals(DownloadAdapter.TankCardControls(start = true, stop = false, progress = false), controls(DownloadState.INVALID))
    }
}
