package dev.wallpad.bridge.data

import dev.wallpad.bridge.analysis.CapturedPacket
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureExporterTest {
    @Test
    fun exportsCsvAndJsonWithoutLosingDirectionOrBytes() {
        val packets = listOf(
            CapturedPacket(42, CapturedPacket.Direction.RX, byteArrayOf(0xAA.toByte(), 1), false),
        )
        assertTrue(CaptureExporter.csv(packets).contains("42,RX,\"AA 01\",false"))
        assertTrue(CaptureExporter.json(packets).contains("\"hex\": \"AA 01\""))
    }
}
