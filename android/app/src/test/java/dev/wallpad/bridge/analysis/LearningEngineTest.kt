package dev.wallpad.bridge.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEngineTest {
    private val engine = LearningEngine()

    @Test
    fun ranksRealPacketFieldsAndIgnoresSyntheticData() {
        val packets = listOf(
            packet(0, byteArrayOf(0xAA.toByte(), 0x10, 0x00)),
            packet(100, byteArrayOf(0xAA.toByte(), 0x10, 0x01)),
            packet(200, byteArrayOf(0xAA.toByte(), 0x10, 0x00)),
            CapturedPacket(300, CapturedPacket.Direction.RX, byteArrayOf(1, 2, 3), true),
        )
        val ranked = engine.rankFields(packets)
        assertEquals("상태/명령 후보", ranked.first { it.offset == 2 }.suggestedRole)
        assertEquals("고정 헤더 후보", ranked.first { it.offset == 0 }.suggestedRole)
    }

    @Test
    fun comparesBaselineAndActionOffsets() {
        val comparison = engine.compareSessions(
            listOf(packet(0, byteArrayOf(1, 2, 3))),
            listOf(packet(1, byteArrayOf(1, 9, 3))),
        )
        assertEquals(listOf(1), comparison.changedOffsets)
        assertTrue(comparison.stableOffsets.containsAll(listOf(0, 2)))
    }

    private fun packet(time: Long, bytes: ByteArray) =
        CapturedPacket(time, CapturedPacket.Direction.RX, bytes, false)
}
