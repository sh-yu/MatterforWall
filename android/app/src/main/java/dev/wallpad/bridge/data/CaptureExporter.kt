package dev.wallpad.bridge.data

import dev.wallpad.bridge.analysis.CapturedPacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ExportPacket(
    val timestampMicros: Long,
    val direction: String,
    val hex: String,
    val syntheticTestData: Boolean,
)

object CaptureExporter {
    private val json = Json { prettyPrint = true }

    fun csv(packets: List<CapturedPacket>): String = buildString {
        appendLine("timestamp_us,direction,hex,synthetic_test_data")
        packets.forEach { packet ->
            append(packet.timestampMicros).append(',')
            append(packet.direction.name).append(',')
            append('"').append(packet.hex).append('"').append(',')
            appendLine(packet.syntheticTestData)
        }
    }

    fun json(packets: List<CapturedPacket>): String = json.encodeToString(
        packets.map {
            ExportPacket(it.timestampMicros, it.direction.name, it.hex, it.syntheticTestData)
        },
    )
}
