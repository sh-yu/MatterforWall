package dev.wallpad.bridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleFrameCodecTest {
    @Test fun roundTripAndCrcRejection() {
        val frame = BleFrame(type = MessageType.PACKET_STREAM, flags = 1u, sequence = 0x1234u,
            payload = byteArrayOf(1, 2, 3))
        val encoded = BleFrameCodec.encode(frame)
        val decoded = BleFrameCodec.decode(encoded)!!
        assertEquals(frame.sequence, decoded.sequence)
        assertArrayEquals(frame.payload, decoded.payload)
        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()
        assertNull(BleFrameCodec.decode(encoded))
    }
}

