package dev.wallpad.bridge.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FirmwareCacheTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun verifiedCacheAvoidsNetworkAndRejectsCorruption() {
        // SYNTHETIC_TEST_DATA: format fixture, never a deployable image.
        val bytes = ByteArray(8192)
        bytes[0] = 0xe9.toByte()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0xabcd5432.toInt())
        "0.4.3".encodeToByteArray().copyInto(bytes, 48)
        "wallpad-matter-bridge".encodeToByteArray().copyInto(bytes, 80)
        bytes[4096] = 0xe7.toByte()
        val hash = FirmwareImageValidator.validate(bytes).getOrThrow().sha256Hex
        val release = FirmwareRelease("0.4.3", "http://invalid.example/never-requested", bytes.size, hash)
        val cache = directory.newFolder("firmware-updates")
        val file = java.io.File(cache, "$hash.bin")
        file.writeBytes(bytes)
        assertArrayEquals(bytes, GitHubReleaseRepository().downloadCached(release, cache) {})
        bytes[200] = 1
        file.writeBytes(bytes)
        // Invalid HTTP URL fails locally before networking if corrupt cache was rejected.
        assertTrue(runCatching { GitHubReleaseRepository().downloadCached(release, cache) {} }.isFailure)
    }
}
