package dev.wallpad.bridge.analysis

enum class LearningPhase { IDLE, BASELINE, ACTION, READY, REVIEW }

data class LearningRound(val baseline: List<CapturedPacket>, val action: List<CapturedPacket>)
data class LearnedCandidate(
    val hex: String,
    val roundsSeen: Int,
    val checksumCandidates: Set<ChecksumCandidate>,
)
data class GuidedLearningState(
    val device: String = "조명",
    val action: String = "켜기",
    val phase: LearningPhase = LearningPhase.IDLE,
    val secondsLeft: Int = 0,
    val overflow: Boolean = false,
    val baseline: List<CapturedPacket> = emptyList(),
    val actionPackets: List<CapturedPacket> = emptyList(),
    val rounds: List<LearningRound> = emptyList(),
    val candidates: List<LearnedCandidate> = emptyList(),
    val message: String = "장치와 동작을 선택하고 시작하세요.",
)

object GuidedLearning {
    const val MAX_FRAMES = 500
    fun candidates(rounds: List<LearningRound>): List<LearnedCandidate> {
        if (rounds.size < 3) return emptyList()
        val analyzer = PacketAnalyzer()
        // Baseline traffic is excluded, not interpreted as a command or ACK.
        val perRound = rounds.map { round ->
            val background = round.baseline.filterNot { it.syntheticTestData }.map { it.hex }.toSet()
            round.action.filterNot { it.syntheticTestData || it.hex in background }
                .distinctBy { it.hex }.associateBy { it.hex }
        }
        return perRound.flatMap { it.keys }.distinct().mapNotNull { hex ->
            val samples = perRound.mapNotNull { it[hex] }
            if (samples.size < 3) null else LearnedCandidate(
                hex, samples.size, analyzer.checksumCandidates(samples.first().bytes),
            )
        }.sortedWith(compareByDescending<LearnedCandidate> { it.roundsSeen }.thenBy { it.hex })
    }
}
