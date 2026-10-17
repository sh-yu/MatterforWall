package dev.wallpad.bridge.ota

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareImageValidatorTest {
    @Test
    fun acceptsSignedProjectImage() {
        val image = validImage("0.4.1")
        val result = FirmwareImageValidator.validate(image, "0.4.0")
        assertTrue(result.isSuccess)
        assertEquals("0.4.1", result.getOrThrow().version)
    }

    @Test
    fun rejectsDowngrade() {
        val result = FirmwareImageValidator.validate(validImage("0.3.9"), "0.4.0")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsUnsignedImage() {
        val image = validImage("0.4.1").also { it[it.size - 4096] = 0 }
        assertTrue(FirmwareImageValidator.validate(image).isFailure)
    }

    private fun validImage(version: String): ByteArray {
        val image = ByteArray(8192)
        image[0] = 0xe9.toByte()
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0xabcd5432.toInt())
        version.encodeToByteArray().copyInto(image, 48)
        "wallpad-matter-bridge".encodeToByteArray().copyInto(image, 80)
        image[image.size - 4096] = 0xe7.toByte()
        return image
    }
}
