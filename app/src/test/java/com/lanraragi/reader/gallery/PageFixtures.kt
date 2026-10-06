package com.lanraragi.reader.gallery

import kotlin.random.Random

/** Page files with valid magic whose bodies do not decode (audit 2026-10-06d PERF-01). */
internal object PageFixtures {

    private fun withNoise(header: ByteArray): ByteArray =
        header + Random(7).nextBytes(BODY_SIZE)

    /** ISO-BMFF "ftyp" box with the AVIF major brand. */
    val avif: ByteArray = withNoise(
        byteArrayOf(0, 0, 0, 0x1C) + "ftypavif".toByteArray() + byteArrayOf(0, 0, 0, 0) +
            "avifmif1miaf".toByteArray()
    )

    /** JPEG XL naked codestream signature. */
    val jxl: ByteArray = withNoise(byteArrayOf(0xFF.toByte(), 0x0A))

    /** PNG signature followed by noise: a known, decodable format with damaged bytes. */
    val damagedPng: ByteArray = withNoise(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    )

    private const val BODY_SIZE = 2048
}
