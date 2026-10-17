package dev.wallpad.bridge.analysis

import kotlin.math.abs

data class CapturedPacket(val timestampMicros: Long, val direction: Direction,
    val bytes: ByteArray, val syntheticTestData: Boolean) {
    enum class Direction { RX, TX }
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
}
data class PacketGroup(val exemplar: ByteArray, val count: Int, val estimatedPeriodMicros: Long?)
data class ByteChange(val offset: Int, val before: UByte?, val after: UByte?)
enum class ChecksumCandidate { XOR8, SUM8, CRC16_CCITT }

class PacketAnalyzer {
    fun group(packets: List<CapturedPacket>): List<PacketGroup> = packets.groupBy { it.bytes.toList() }
        .map { (_, members) ->
            val intervals = members.zipWithNext { a, b -> b.timestampMicros - a.timestampMicros }.filter { it >= 0 }
            PacketGroup(members.first().bytes, members.size,
                intervals.takeIf { it.isNotEmpty() }?.average()?.toLong())
        }.sortedByDescending(PacketGroup::count)
    fun differences(before: ByteArray, after: ByteArray): List<ByteChange> =
        (0 until maxOf(before.size, after.size)).mapNotNull { index ->
            val left = before.getOrNull(index)?.toUByte()
            val right = after.getOrNull(index)?.toUByte()
            ByteChange(index, left, right).takeIf { left != right }
        }
    fun checksumCandidates(frame: ByteArray): Set<ChecksumCandidate> {
        val result = mutableSetOf<ChecksumCandidate>()
        if (frame.size >= 2) {
            val body = frame.dropLast(1)
            val tail = frame.last().toUByte().toInt()
            if (body.fold(0) { acc, b -> acc xor b.toUByte().toInt() } == tail) result += ChecksumCandidate.XOR8
            if ((body.sumOf { it.toUByte().toInt() } and 0xff) == tail) result += ChecksumCandidate.SUM8
        }
        if (frame.size >= 3) {
            val expected = frame[frame.lastIndex - 1].toUByte().toInt() or (frame.last().toUByte().toInt() shl 8)
            if (dev.wallpad.bridge.protocol.BleFrameCodec.crc16Ccitt(frame.copyOf(frame.size - 2)) == expected) result += ChecksumCandidate.CRC16_CCITT
        }
        return result
    }
    fun correlate(commands: List<CapturedPacket>, responses: List<CapturedPacket>, windowMicros: Long) =
        commands.associateWith { command -> responses.filter { it.timestampMicros >= command.timestampMicros && abs(it.timestampMicros - command.timestampMicros) <= windowMicros } }
}
