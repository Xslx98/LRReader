package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FlexibleStringSerializerTest {

    @Serializable
    data class TestWrapper(
        @Serializable(with = FlexibleStringSerializer::class) val value: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deserializeString() {
        val result = json.decodeFromString<TestWrapper>("""{"value": "hello"}""")
        assertEquals("hello", result.value)
    }

    @Test
    fun deserializeBoolean() {
        val result = json.decodeFromString<TestWrapper>("""{"value": true}""")
        assertEquals("true", result.value)
    }

    @Test
    fun deserializeBooleanFalse() {
        val result = json.decodeFromString<TestWrapper>("""{"value": false}""")
        assertEquals("false", result.value)
    }

    @Test
    fun deserializeInt() {
        val result = json.decodeFromString<TestWrapper>("""{"value": 1}""")
        assertEquals("1", result.value)
    }

    @Test
    fun deserializeZero() {
        val result = json.decodeFromString<TestWrapper>("""{"value": 0}""")
        assertEquals("0", result.value)
    }

    @Test
    fun serializeString() {
        val wrapper = TestWrapper("test")
        val encoded = json.encodeToString(TestWrapper.serializer(), wrapper)
        assertTrue(encoded.contains("\"test\""))
    }
}
