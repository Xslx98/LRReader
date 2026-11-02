package com.lanraragi.reader.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.server.LRRSecureStorageUnavailableException
import com.lanraragi.reader.server.SecureCredentialStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pattern hash/verify round trips of [AppLockCredentials] on a plain
 * SharedPreferences injected through [SecureCredentialStore.initializeForTesting]
 * (Robolectric has no AndroidKeyStore).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AppLockCredentialsTest {

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        SecureCredentialStore.initialize(ctx)
        SecureCredentialStore.initializeForTesting(
            ctx.getSharedPreferences("lrr_auth_test", Context.MODE_PRIVATE)
        )
    }

    @After
    fun tearDown() {
        SecureCredentialStore.clear()
    }

    // ── Pattern hash/verify ─────────────────────────────────────────

    @Test
    fun hasPattern_falseBeforeSet() {
        assertFalse(AppLockCredentials.hasPattern())
    }

    @Test
    fun setPattern_thenHasPattern() {
        AppLockCredentials.setPattern("1234")
        assertTrue(AppLockCredentials.hasPattern())
    }

    @Test
    fun verifyPattern_correctInput_returnsTrue() {
        AppLockCredentials.setPattern("secret")
        assertTrue(AppLockCredentials.verifyPattern("secret"))
    }

    @Test
    fun verifyPattern_wrongInput_returnsFalse() {
        AppLockCredentials.setPattern("secret")
        assertFalse(AppLockCredentials.verifyPattern("wrong"))
    }

    @Test
    fun verifyPattern_emptyInput_returnsFalse() {
        AppLockCredentials.setPattern("secret")
        assertFalse(AppLockCredentials.verifyPattern(""))
    }

    @Test
    fun verifyPattern_nullInput_returnsFalse() {
        AppLockCredentials.setPattern("secret")
        assertFalse(AppLockCredentials.verifyPattern(null))
    }

    @Test
    fun setPattern_null_clearsPattern() {
        AppLockCredentials.setPattern("secret")
        AppLockCredentials.setPattern(null)
        assertFalse(AppLockCredentials.hasPattern())
        assertFalse(AppLockCredentials.verifyPattern("secret"))
    }

    @Test
    fun setPattern_overwritesPriorHash_stillVerifiesCorrectly() {
        // Same pattern stored twice should still verify correctly (different salts but same result)
        AppLockCredentials.setPattern("same")
        assertTrue(AppLockCredentials.verifyPattern("same"))
        AppLockCredentials.setPattern("same")
        assertTrue(AppLockCredentials.verifyPattern("same"))
    }

    @Test(expected = LRRSecureStorageUnavailableException::class)
    fun setPattern_throwsWhenStorageUnavailable() {
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        AppLockCredentials.setPattern("1234")
    }

    @Test
    fun verifyPattern_returnsFalseWhenStorageUnavailable() {
        // Reads MUST NOT throw — verify-before-unlock flows need to degrade gracefully.
        SecureCredentialStore.simulateStorageUnavailableForTesting()
        assertFalse(AppLockCredentials.verifyPattern("anything"))
    }
}
