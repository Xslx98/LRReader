package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRArchiveApi
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Temporary: reports raw server answers through a failure message. Removed with the mutation proof. */
class ProbeContractTest {
    @Test
    fun probe() = contractTest {
        val url = ContractServer.baseUrl
        val stats = rawGet("$url/api/database/stats").decodeToString()
        val at = stats.indexOf("contractmisc")
        val spare = File(ContractServer.seed.spare.file)
        val id = LRRArchiveApi.uploadArchive(ContractServer.client, url, spare)
        val name = LRRArchiveApi.deleteArchive(ContractServer.client, url, id)
        fail("PROBE stats=[${stats.substring(maxOf(0, at - 60), minOf(stats.length, at + 60))}] deleted=[$name]")
    }
}
