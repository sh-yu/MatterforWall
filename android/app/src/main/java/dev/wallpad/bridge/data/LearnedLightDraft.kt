package dev.wallpad.bridge.data

import dev.wallpad.bridge.analysis.CapturedPacket
import dev.wallpad.bridge.analysis.GuidedLearningState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LearningEvidence(val round: Int, val phase: String, val timestampMicros: Long, val hex: String)

@Serializable
data class LearnedLightDraft(
    val schemaVersion: Int = 1,
    val bridgeAddress: String,
    val name: String,
    val action: String,
    val candidateHex: String,
    val roundsSeen: Int,
    val evidence: List<LearningEvidence>,
    val verification: String = "HARDWARE_TEST_PENDING",
)

object LearnedLightDrafts {
    private val json = Json { ignoreUnknownKeys = true }
    const val LIMIT = 32

    fun create(bridge: String, name: String, learning: GuidedLearningState, hex: String): LearnedLightDraft {
        require(bridge.isNotBlank())
        require(name.trim().length in 1..48)
        require(learning.action in setOf("켜기", "끄기"))
        val candidate = learning.candidates.single { it.hex == hex }
        require(candidate.roundsSeen >= 3)
        val evidence = learning.rounds.flatMapIndexed { index, round ->
            fun collect(phase: String, packets: List<CapturedPacket>) = packets
                .filterNot { it.syntheticTestData }.map {
                    LearningEvidence(index + 1, phase, it.timestampMicros, it.hex)
                }
            collect("baseline", round.baseline) + collect("action", round.action)
        }
        return LearnedLightDraft(bridgeAddress = bridge, name = name.trim(), action = learning.action,
            candidateHex = hex, roundsSeen = candidate.roundsSeen, evidence = evidence)
    }

    fun encode(drafts: List<LearnedLightDraft>): String {
        require(drafts.size <= LIMIT)
        require(drafts.all { it.verification == "HARDWARE_TEST_PENDING" })
        return json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LearnedLightDraft.serializer()), drafts)
    }

    fun decode(text: String): List<LearnedLightDraft> {
        require(text.length <= 16 * 1024 * 1024)
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LearnedLightDraft.serializer()), text).also {
            require(it.size <= LIMIT && it.all { draft -> draft.verification == "HARDWARE_TEST_PENDING" })
        }
    }
}
