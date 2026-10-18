package dev.wallpad.bridge.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaTransferSizingTest {
    @Test fun fitsNegotiatedLimits() {
        assertEquals(7, OtaTransferSizing.chunkSize(23))
        assertEquals(231, OtaTransferSizing.chunkSize(247))
        assertEquals(499, OtaTransferSizing.chunkSize(517))
        for (mtu in 23..517) {
            val size = OtaTransferSizing.chunkSize(mtu) + 13
            assertTrue(size <= mtu - 3 && size <= 512)
        }
    }
}
