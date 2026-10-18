package dev.wallpad.bridge.analysis

import dev.wallpad.bridge.data.LearnedLightDrafts
import org.junit.Assert.assertEquals
import org.junit.Test

class LearnedLightDraftTest {
    @Test fun savesEvidenceButNeverGrantsControl() {
        // SYNTHETIC_TEST_DATA: test-only data; not a real command profile.
        val packet = CapturedPacket(100, CapturedPacket.Direction.RX, byteArrayOf(1, 2, 3), false)
        val rounds = List(3) { LearningRound(emptyList(), listOf(packet)) }
        val learning = GuidedLearningState(rounds = rounds, candidates = GuidedLearning.candidates(rounds))
        val draft = LearnedLightDrafts.create("test-bridge", " 거실 ", learning, "01 02 03")
        assertEquals("거실", draft.name)
        assertEquals("HARDWARE_TEST_PENDING", draft.verification)
        assertEquals(3, draft.evidence.size)
        assertEquals(listOf(draft), LearnedLightDrafts.decode(LearnedLightDrafts.encode(listOf(draft))))
    }
    @Test(expected = IllegalArgumentException::class)
    fun refusesToPersistAClaimOfVerifiedControl() {
        val packet = CapturedPacket(1, CapturedPacket.Direction.RX, byteArrayOf(1), false)
        val rounds = List(3) { LearningRound(emptyList(), listOf(packet)) }
        val learning = GuidedLearningState(rounds = rounds, candidates = GuidedLearning.candidates(rounds))
        val draft = LearnedLightDrafts.create("test", "light", learning, "01")
        LearnedLightDrafts.encode(listOf(draft.copy(verification = "CONTROL_VERIFIED")))
    }
}
