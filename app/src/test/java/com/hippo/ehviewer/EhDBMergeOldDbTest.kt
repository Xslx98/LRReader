package com.hippo.ehviewer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.dao.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation

/**
 * Tests for [EhDB.mergeOldDB] after W1-7: the legacy SQLite-to-Room merge is now
 * a `suspend fun` with no internal `runBlocking`. The caller in `EhApplication`
 * already runs it on a `Dispatchers.IO`-backed [kotlinx.coroutines.CoroutineScope],
 * so the previous `runBlocking` was pinning an IO worker for nothing.
 *
 * Tests verify:
 * 1. The function is reflectively `suspend` (no JVM `@JvmStatic` bridge).
 * 2. The function is callable from a `runTest { ... }` coroutine block (compile-time
 *    proof of the `suspend` modifier).
 * 3. With no legacy `data` SQLite database present, the function is a clean no-op
 *    that does not throw and leaves the Room DB untouched.
 * 4. The function does not retain any Java-callable static bridge (regression guard
 *    against accidentally re-introducing `@JvmStatic`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class EhDBMergeOldDbTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // In-memory Room DB injected directly into EhDB.sDatabase via reflection,
        // bypassing EhDB.initialize() (which depends on Settings).
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        // Make sure no stale legacy DB exists from a previous test run
        context.getDatabasePath("data")?.takeIf { it.exists() }?.delete()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mergeOldDB_isSuspendFunction() {
        // Suspend functions are compiled to take a trailing `Continuation` parameter.
        // Locate the JVM method and verify its parameter list ends with Continuation.
        val method = EhDB::class.java.declaredMethods.firstOrNull { it.name == "mergeOldDB" }
        assertNotNull("mergeOldDB must exist on EhDB", method)
        val params = method!!.parameterTypes
        assertTrue(
            "mergeOldDB must accept (Context, Continuation) — i.e. be a suspend fun (W1-7)",
            params.size == 2 && Continuation::class.java.isAssignableFrom(params[1])
        )
    }

    @Test
    fun mergeOldDB_hasNoJvmStaticBridge() {
        // `@JvmStatic` on an `object` member compiles a STATIC method directly on the
        // EhDB Java class (in addition to the instance method on the singleton).
        // W1-7 explicitly drops `@JvmStatic` because suspend functions can't be
        // bridged usefully through it. Verify no static `mergeOldDB` exists.
        val staticMethod = EhDB::class.java.declaredMethods.firstOrNull {
            it.name == "mergeOldDB" && java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        assertTrue(
            "mergeOldDB must NOT have a @JvmStatic static bridge (W1-7)",
            staticMethod == null
        )
    }

    @Test
    fun mergeOldDB_callableFromCoroutine_noOpWhenNoLegacyDb() = runTest {
        // Compile-time proof: this call only compiles if mergeOldDB is `suspend`.
        // Behavioural proof: with no legacy `data` SQLite file, the SQLiteOpenHelper
        // creates an empty schema, the rawQuery loops short-circuit (no rows), the
        // helper closes, and the function returns cleanly without touching Room.
        EhDB.mergeOldDB(context)

        // Verify the Room DB was not mutated as a side effect.
        assertEquals(0, db.downloadDao().getAllDownloadInfo().size)
        assertTrue(db.browsingDao().getAllQuickSearch().isEmpty())
        assertTrue(db.browsingDao().getAllHistory().isEmpty())
    }

    @Test
    fun mergeOldDB_canBeCalledTwice_idempotent() = runTest {
        // Calling twice in a row should not throw, and `sHasOldDB` should remain false.
        EhDB.mergeOldDB(context)
        EhDB.mergeOldDB(context)
        assertFalse("needMerge() should be false after merge completes", EhDB.needMerge())
    }

    @Test
    fun mergeOldDB_javaMethodIsNotStatic() {
        // Cross-check: the single `mergeOldDB` method on the EhDB Java class must
        // be an instance method on the EhDB singleton, not a static.
        val method = EhDB::class.java.declaredMethods.first { it.name == "mergeOldDB" }
        assertFalse(
            "mergeOldDB Java method must NOT be static (W1-7 drops @JvmStatic)",
            java.lang.reflect.Modifier.isStatic(method.modifiers)
        )
    }
}
