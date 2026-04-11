package com.hippo.ehviewer.module

import com.hippo.ehviewer.ServiceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the [Cacheable] self-registration pattern in [ServiceRegistry].
 *
 * Verifies that:
 * - Registering a [Cacheable] and calling [ServiceRegistry.clearAllCaches] invokes
 *   [Cacheable.clearCache] on all registered instances
 * - Multiple cacheables are all invoked
 * - [ServiceRegistry.initializeForTest] clears previously registered cacheables
 */
class CacheableTest {

    @Before
    fun setUp() {
        // Reset ServiceRegistry to a clean state before each test.
        // Pass null for coroutine to avoid Dispatchers.Main unavailability in unit tests.
        ServiceRegistry.initializeForTest(coroutine = null)
    }

    @Test
    fun clearAllCaches_invokesClearCache_onRegisteredCacheable() {
        var cleared = false
        val cacheable = object : Cacheable {
            override fun clearCache() {
                cleared = true
            }
        }
        ServiceRegistry.registerCacheable(cacheable)

        ServiceRegistry.clearAllCaches()

        assertTrue("clearCache() should have been invoked", cleared)
    }

    @Test
    fun clearAllCaches_invokesAllRegisteredCacheables() {
        val invocations = mutableListOf<String>()

        val first = object : Cacheable {
            override fun clearCache() {
                invocations.add("first")
            }
        }
        val second = object : Cacheable {
            override fun clearCache() {
                invocations.add("second")
            }
        }
        val third = object : Cacheable {
            override fun clearCache() {
                invocations.add("third")
            }
        }

        ServiceRegistry.registerCacheable(first)
        ServiceRegistry.registerCacheable(second)
        ServiceRegistry.registerCacheable(third)

        ServiceRegistry.clearAllCaches()

        assertEquals(
            "All three cacheables should be invoked in registration order",
            listOf("first", "second", "third"),
            invocations
        )
    }

    @Test
    fun clearAllCaches_doesNothing_whenNoCacheablesRegistered() {
        // Should not throw
        ServiceRegistry.clearAllCaches()
    }

    @Test
    fun initializeForTest_clearsRegisteredCacheables() {
        var cleared = false
        val cacheable = object : Cacheable {
            override fun clearCache() {
                cleared = true
            }
        }
        ServiceRegistry.registerCacheable(cacheable)

        // Re-initialize — should clear the registration list
        ServiceRegistry.initializeForTest(coroutine = null)
        ServiceRegistry.clearAllCaches()

        assertTrue(
            "clearCache() should NOT have been invoked after initializeForTest() cleared registrations",
            !cleared
        )
    }

    @Test
    fun clearAllCaches_canBeCalledMultipleTimes() {
        var count = 0
        val cacheable = object : Cacheable {
            override fun clearCache() {
                count++
            }
        }
        ServiceRegistry.registerCacheable(cacheable)

        ServiceRegistry.clearAllCaches()
        ServiceRegistry.clearAllCaches()
        ServiceRegistry.clearAllCaches()

        assertEquals("clearCache() should have been invoked 3 times", 3, count)
    }

    @Test
    fun sameCacheable_registeredTwice_invokedTwice() {
        var count = 0
        val cacheable = object : Cacheable {
            override fun clearCache() {
                count++
            }
        }
        ServiceRegistry.registerCacheable(cacheable)
        ServiceRegistry.registerCacheable(cacheable)

        ServiceRegistry.clearAllCaches()

        assertEquals(
            "Same cacheable registered twice should be invoked twice",
            2,
            count
        )
    }
}
