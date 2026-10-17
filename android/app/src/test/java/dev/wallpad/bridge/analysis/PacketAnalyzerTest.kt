package dev.wallpad.bridge.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketAnalyzerTest {
    private val analyzer = PacketAnalyzer()
    @Test fun groupsAndDiffsSyntheticPackets() {
        val packets = listOf(
            CapturedPacket(100, CapturedPacket.Direction.RX, byteArrayOf(1, 2, 3), true),
            CapturedPacket(200, CapturedPacket.Direction.RX, byteArrayOf(1, 2, 3), true),
            CapturedPacket(250, CapturedPacket.Direction.RX, byteArrayOf(1, 9, 3), true),
        )
        assertEquals(2, analyzer.group(packets).size)
        assertEquals(2, analyzer.group(packets).first().count)
        assertEquals(1, analyzer.differences(packets[0].bytes, packets[2].bytes).single().offset)
        assertTrue(ChecksumCandidate.SUM8 in analyzer.checksumCandidates(byteArrayOf(1, 2, 3)))
    }
}

