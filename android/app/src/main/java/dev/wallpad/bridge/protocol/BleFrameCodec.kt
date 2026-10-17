package dev.wallpad.bridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val BLE_PROTOCOL_VERSION: UByte = 1u
const val MAX_PAYLOAD = 4096

enum class MessageType(val wire: UByte) {
    DEVICE_INFO(0x01u), CONFIGURATION(0x02u), PACKET_STREAM(0x03u),
    COMMAND(0x04u), RESPONSE(0x05u), DIAGNOSTICS(0x06u),
    OTA_CONTROL(0x07u), OTA_DATA(0x08u), ERROR(0x7fu);
    companion object { fun fromWire(value: UByte) = entries.firstOrNull { it.wire == value } }
}

data class BleFrame(
    val version: UByte = BLE_PROTOCOL_VERSION,
    val type: MessageType,
    val flags: UByte = 0u,
    val sequence: UShort,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is BleFrame && version == other.version &&
        type == other.type && flags == other.flags && sequence == other.sequence &&
        payload.contentEquals(other.payload)
    override fun hashCode(): Int = 31 * sequence.hashCode() + payload.contentHashCode()
}

object BleFrameCodec {
    private const val HEADER = 7
    fun encode(frame: BleFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD) { "payload too large" }
        val withoutCrc = ByteBuffer.allocate(HEADER + frame.payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(frame.version.toByte()).put(frame.type.wire.toByte()).put(frame.flags.toByte())
            .putShort(frame.sequence.toShort()).putShort(frame.payload.size.toShort())
            .put(frame.payload).array()
        val crc = crc16Ccitt(withoutCrc)
        return withoutCrc + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }
    fun decode(data: ByteArray): BleFrame? {
        if (data.size < HEADER + 2) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get().toUByte()
        val type = MessageType.fromWire(buffer.get().toUByte()) ?: return null
        val flags = buffer.get().toUByte()
        val sequence = buffer.short.toUShort()
        val length = buffer.short.toUShort().toInt()
        if (version != BLE_PROTOCOL_VERSION || length > MAX_PAYLOAD || data.size != HEADER + length + 2) return null
        val payload = ByteArray(length).also(buffer::get)
        val expected = buffer.short.toUShort().toInt()
        if (crc16Ccitt(data.copyOf(data.size - 2)) != expected) return null
        return BleFrame(version, type, flags, sequence, payload)
    }
    fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xffff
        data.forEach { byte ->
            crc = crc xor (byte.toUByte().toInt() shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }
}

class BleFrameReassembler(
    private val maxPayload: Int = MAX_PAYLOAD,
    private val timeoutMillis: Long = 5_000,
) {
    private data class Pending(
        val type: MessageType,
        val sequence: UShort,
        val startedAtMillis: Long,
        val payload: MutableList<Byte>,
    )

    private var pending: Pending? = null

    fun accept(frame: BleFrame, nowMillis: Long = System.currentTimeMillis()): BleFrame? {
        val flags = frame.flags.toInt()
        if (flags and FLAG_FRAGMENTED == 0) return frame.takeIf { it.payload.size <= maxPayload }

        pending?.takeIf { nowMillis - it.startedAtMillis > timeoutMillis }?.let { pending = null }
        val first = flags and FLAG_FIRST != 0
        val last = flags and FLAG_LAST != 0
        if (first) {
            pending = Pending(frame.type, frame.sequence, nowMillis, mutableListOf())
        } else if (pending?.type != frame.type || pending?.sequence != frame.sequence) {
            pending = null
            return null
        }

        val current = pending ?: return null
        if (current.payload.size + frame.payload.size > maxPayload) {
            pending = null
            return null
        }
        current.payload += frame.payload.toList()
        if (!last) return null
        pending = null
        return frame.copy(flags = 0u, payload = current.payload.toByteArray())
    }

    fun reset() {
        pending = null
    }

    private companion object {
        const val FLAG_FRAGMENTED = 1 shl 1
        const val FLAG_FIRST = 1 shl 2
        const val FLAG_LAST = 1 shl 3
    }
}
