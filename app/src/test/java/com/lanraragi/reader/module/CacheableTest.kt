package com.lanraragi.reader.module

import com.lanraragi.reader.ServiceRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the [Cacheable] self-registration pattern in [ServiceRegistry].
 *
 * Verifies that [ServiceRegistry.clearAllCaches] invokes [Cacheable.clearCache] on
 * every registered instance, in registration order.
 */
class CacheableTest {

    @Before
    fun setUp() {
        // Reset ServiceRegistry to a clean state before each test.
        // Pass null for coroutine to avoid Dispatchers.Main unavailability in unit tests.
        ServiceRegistry.initializeForTest(coroutine = null)
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
}
