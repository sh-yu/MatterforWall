package dev.wallpad.bridge.analysis

import kotlin.math.ln

data class FieldCandidate(
    val offset: Int,
    val distinctValues: Int,
    val entropyBits: Double,
    val confidence: Double,
    val suggestedRole: String,
)

data class SessionComparison(
    val changedOffsets: List<Int>,
    val stableOffsets: List<Int>,
    val baselineFrames: Int,
    val actionFrames: Int,
)

class LearningEngine {
    fun rankFields(packets: List<CapturedPacket>): List<FieldCandidate> {
        val frames = packets.filterNot { it.syntheticTestData }.map(CapturedPacket::bytes)
        if (frames.isEmpty()) return emptyList()
        val width = frames.maxOf(ByteArray::size)
        return (0 until width).mapNotNull { offset ->
            val values = frames.mapNotNull { it.getOrNull(offset)?.toUByte()?.toInt() }
            if (values.size < 2) return@mapNotNull null
            val counts = values.groupingBy { it }.eachCount()
            val entropy = counts.values.sumOf { count ->
                val probability = count.toDouble() / values.size
                -probability * ln(probability) / ln(2.0)
            }
            val distinct = counts.size
            val stability = counts.values.max().toDouble() / values.size
            val role = when {
                distinct == 1 -> "고정 헤더 후보"
                distinct in 2..8 && stability < 0.98 -> "상태/명령 후보"
                distinct in 2..32 -> "주소 후보"
                else -> "동적 데이터 후보"
            }
            val confidence = when (role) {
                "고정 헤더 후보" -> stability
                "상태/명령 후보" -> (1.0 - stability).coerceIn(0.0, 1.0)
                "주소 후보" -> (distinct / 32.0).coerceIn(0.0, 1.0)
                else -> (entropy / 8.0).coerceIn(0.0, 1.0)
            }
            FieldCandidate(offset, distinct, entropy, confidence, role)
        }.sortedWith(compareByDescending<FieldCandidate> { it.confidence }.thenBy { it.offset })
    }

    fun compareSessions(
        baseline: List<CapturedPacket>,
        action: List<CapturedPacket>,
    ): SessionComparison {
        val baselineBytes = baseline.filterNot { it.syntheticTestData }.map(CapturedPacket::bytes)
        val actionBytes = action.filterNot { it.syntheticTestData }.map(CapturedPacket::bytes)
        val width = (baselineBytes + actionBytes).maxOfOrNull(ByteArray::size) ?: 0
        val changed = mutableListOf<Int>()
        val stable = mutableListOf<Int>()
        for (offset in 0 until width) {
            val before = baselineBytes.mapNotNull { it.getOrNull(offset) }.toSet()
            val after = actionBytes.mapNotNull { it.getOrNull(offset) }.toSet()
            if (before == after && before.isNotEmpty()) stable += offset else changed += offset
        }
        return SessionComparison(changed, stable, baselineBytes.size, actionBytes.size)
    }

    fun detectPeriods(packets: List<CapturedPacket>, tolerance: Double = 0.15): List<PacketGroup> =
        PacketAnalyzer().group(packets).filter { group ->
            val matching = packets.filter { it.bytes.contentEquals(group.exemplar) }
            val intervals = matching.zipWithNext { left, right -> right.timestampMicros - left.timestampMicros }
                .filter { it > 0 }
            if (intervals.size < 2) return@filter false
            val average = intervals.average()
            intervals.all { kotlin.math.abs(it - average) <= average * tolerance }
        }
}
