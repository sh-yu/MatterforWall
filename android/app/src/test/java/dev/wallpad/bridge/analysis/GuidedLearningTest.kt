package dev.wallpad.bridge.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedLearningTest {
    // SYNTHETIC_TEST_DATA: algorithm fixtures, never a real protocol profile.
    private fun packet(vararg bytes: Int, synthetic: Boolean = false) = CapturedPacket(
        0, CapturedPacket.Direction.RX, bytes.map { it.toByte() }.toByteArray(), synthetic,
    )
    @Test fun requiresThreeIndependentRounds() {
        val round = LearningRound(listOf(packet(9, 9)), listOf(packet(1, 2, 3)))
        assertTrue(GuidedLearning.candidates(List(2) { round }).isEmpty())
        val result = GuidedLearning.candidates(List(3) { round })
        assertEquals(1, result.size)
        assertEquals(3, result.single().roundsSeen)
        assertTrue(ChecksumCandidate.SUM8 in result.single().checksumCandidates)
    }
    @Test fun excludesBackgroundSyntheticAndDuplicateInflation() {
        val round = LearningRound(listOf(packet(1, 2, 3)), listOf(
            packet(1, 2, 3), packet(4, 5, synthetic = true), packet(6, 7), packet(6, 7),
        ))
        val result = GuidedLearning.candidates(List(3) { round })
        assertEquals("06 07", result.single().hex)
        assertEquals(3, result.single().roundsSeen)
    }
}
