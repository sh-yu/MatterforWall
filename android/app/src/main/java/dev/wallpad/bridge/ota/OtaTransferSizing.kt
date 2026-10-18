package dev.wallpad.bridge.ota

object OtaTransferSizing {
    // ATT (3), bridge header/CRC (9), OTA offset (4); attribute limit is 512.
    fun chunkSize(mtu: Int): Int {
        require(mtu >= 23)
        return minOf(mtu - 3, 512) - 13
    }
}
