package dev.wallpad.bridge.ota

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun numericOrderingAndUnknownDevice() {
        assertTrue(ReleaseVersion.isNewer("0.4.10", "0.4.9"))
        assertFalse(ReleaseVersion.isNewer("0.4.1", "0.4.1"))
        assertFalse(ReleaseVersion.isNewer("0.4.1", "0.5.0"))
        assertTrue(ReleaseVersion.isNewer("0.4.2", "확인되지 않음"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidVersion() { ReleaseVersion.isNewer("999999999999999999.0.0", "0.4.1") }
}
