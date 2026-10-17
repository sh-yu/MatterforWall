package dev.wallpad.bridge.transport

import dev.wallpad.bridge.analysis.CapturedPacket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, READY, ERROR }

interface BleTransport {
    val packets: Flow<CapturedPacket>
    val connectionState: Flow<ConnectionState>
    suspend fun connect(deviceAddress: String)
    suspend fun disconnect()
    suspend fun sendCommand(command: ByteArray): Result<ByteArray>
}

class MockBleTransport : BleTransport {
    override val connectionState: Flow<ConnectionState> = flow { emit(ConnectionState.READY) }
    override val packets: Flow<CapturedPacket> = flow {
        var tick = 0
        while (true) {
            // SYNTHETIC_TEST_DATA: never interpret this as a KOCOM frame.
            val sample = if (tick++ % 3 == 0) {
                byteArrayOf(0xAA.toByte(), 0x10, 0x01, 0xBB.toByte())
            } else {
                byteArrayOf(0xAA.toByte(), 0x10, 0x00, 0xBA.toByte())
            }
            emit(CapturedPacket(System.nanoTime() / 1_000, CapturedPacket.Direction.RX, sample, true))
            delay(750)
        }
    }
    override suspend fun connect(deviceAddress: String) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun sendCommand(command: ByteArray) = Result.failure<ByteArray>(
        SecurityException("Mock and unverified profiles are receive-only"),
    )
}

