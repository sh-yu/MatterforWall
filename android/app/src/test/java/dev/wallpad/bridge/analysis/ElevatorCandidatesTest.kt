package dev.wallpad.bridge.analysis

import org.junit.Assert.*
import org.junit.Test

class ElevatorCandidatesTest {
    private fun bytes(option: ElevatorOption) = option.sample.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun splitAndCoalescedFrames() {
        val scanner = ElevatorCandidates(ElevatorOption.LONG)
        val frame = bytes(ElevatorOption.LONG)
        assertTrue(scanner.feed(byteArrayOf(0x11) + frame.take(8).toByteArray()).isEmpty())
        val result = scanner.feed(frame.drop(8).toByteArray() + frame)
        assertEquals(2, result.size)
        assertTrue(result.all { it.endsWith("후보 체크섬 일치") })
    }
    @Test fun badChecksumAndUnverifiedShortFrame() {
        val frame = bytes(ElevatorOption.LONG)
        frame[18] = (frame[18].toInt() xor 1).toByte()
        assertTrue(ElevatorCandidates(ElevatorOption.LONG).feed(frame).single().endsWith("체크섬 불일치"))
        assertTrue(ElevatorCandidates(ElevatorOption.SHORT).feed(bytes(ElevatorOption.SHORT)).single().endsWith("체크섬 미검증"))
        frame[0] = 0
        assertTrue(ElevatorCandidates(ElevatorOption.LONG).feed(frame).isEmpty())
    }
}
