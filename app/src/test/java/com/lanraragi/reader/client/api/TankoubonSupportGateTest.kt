package com.lanraragi.reader.client.api

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TankoubonSupportGateTest {

    private val urlA = "http://a:3000"
    private val urlB = "http://b:3000"

    @Before
    fun reset() = TankoubonSupportGate.clear()

    @Test
    fun unknownByDefault() {
        assertEquals(TankoubonSupportGate.Support.UNKNOWN, TankoubonSupportGate.support(urlA))
        assertFalse(TankoubonSupportGate.isUnsupported(urlA))
    }

    @Test
    fun http404_marksUnsupported_perBaseUrl() {
        val marked = TankoubonSupportGate.markFrom(urlA, LRRHttpException(404))
        assertTrue(marked)
        assertTrue(TankoubonSupportGate.isUnsupported(urlA))
        // other server untouched
        assertFalse(TankoubonSupportGate.isUnsupported(urlB))
    }

    @Test
    fun ioErrorsDoNotMark() {
        assertFalse(TankoubonSupportGate.markFrom(urlA, IOException("timeout")))
        assertFalse(TankoubonSupportGate.markFrom(urlA, LRRHttpException(502)))
        assertEquals(TankoubonSupportGate.Support.UNKNOWN, TankoubonSupportGate.support(urlA))
    }

    @Test
    fun markSupported_thenLater404_flipsToUnsupported() {
        TankoubonSupportGate.markSupported(urlA)
        assertEquals(TankoubonSupportGate.Support.SUPPORTED, TankoubonSupportGate.support(urlA))
        TankoubonSupportGate.markFrom(urlA, LRRHttpException(404))
        assertTrue(TankoubonSupportGate.isUnsupported(urlA))
    }

    @Test
    fun trailingSlashNormalised() {
        TankoubonSupportGate.markFrom("$urlA/", LRRHttpException(404))
        assertTrue(TankoubonSupportGate.isUnsupported(urlA))
    }
}
