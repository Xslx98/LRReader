package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.bearerAuthHeaderValue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric for the real android.util.Base64 inside [bearerAuthHeaderValue]:
 * this is the one place that helper meets a server that can refuse it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ContractAuthTest {

    private fun clientSending(header: String) = ContractServer.anonymousClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", header).build())
        }
        .build()

    @Test
    fun productionBearerFormat_isAcceptedForAProtectedWrite() = contractTest {
        val client = clientSending(bearerAuthHeaderValue(ContractServer.apiKey))
        val id = LRRCategoryApi.createCategory(client, ContractServer.baseUrl, "Contract auth")
        LRRCategoryApi.deleteCategory(client, ContractServer.baseUrl, id)
    }

    @Test
    fun wrongKey_isRefused() = contractTest {
        val client = clientSending(bearerAuthHeaderValue("not-the-key"))
        assertHttpFailure(401) { LRRCategoryApi.createCategory(client, ContractServer.baseUrl, "Contract auth") }
    }

    @Test
    fun noKey_isRefused() = contractTest {
        assertHttpFailure(401) {
            LRRCategoryApi.createCategory(ContractServer.anonymousClient, ContractServer.baseUrl, "Contract auth")
        }
    }

    @Test
    fun harness_refusesAnythingButLoopback() {
        requireLoopback("http://127.0.0.1:3000")
        requireLoopback("http://localhost:3000")
        requireLoopback("http://[::1]:3000")
        listOf(
            "http://192.168.1.10:3000",
            "https://lrr.example.com",
            "http://127.0.0.1.example.com:3000",
            "not a url",
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { requireLoopback(url) }
        }
    }
}
