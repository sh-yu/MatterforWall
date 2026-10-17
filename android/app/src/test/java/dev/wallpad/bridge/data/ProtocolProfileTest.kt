package dev.wallpad.bridge.data

import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolProfileTest {
    @Test fun rejectsDangerousAllowList() {
        val profile = ProtocolProfile(1, "KOCOM", "Unknown", "0.1.0", "CONTROL_VERIFIED",
            UartProfile(9600, 8, "NONE", 1), FramingProfile("TIMEOUT", 20, "NONE"),
            emptyMap(), TxPolicy(false, listOf("gas.open")))
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.validate(profile) }
    }
}
