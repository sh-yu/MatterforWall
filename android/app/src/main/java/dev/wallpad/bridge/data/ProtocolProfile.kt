package dev.wallpad.bridge.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UartProfile(val baud: Int, val dataBits: Int, val parity: String, val stopBits: Int)

@Serializable
data class FramingProfile(val mode: String, val frameTimeoutMs: Int, val checksum: String)

@Serializable
data class TxPolicy(val defaultAllow: Boolean, val allowList: List<String>)

@Serializable
data class ProtocolProfile(
    val schemaVersion: Int,
    val manufacturer: String,
    val model: String,
    val profileVersion: String,
    val verification: String,
    val uart: UartProfile,
    val framing: FramingProfile,
    val commands: Map<String, String>,
    val txPolicy: TxPolicy,
)

object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    fun decode(text: String): ProtocolProfile = json.decodeFromString<ProtocolProfile>(text).also(::validate)
    fun encode(profile: ProtocolProfile): String = json.encodeToString(ProtocolProfile.serializer(), profile)
    fun validate(profile: ProtocolProfile) {
        require(profile.schemaVersion == 1)
        require(profile.uart.baud in 1_200..1_000_000)
        require(profile.uart.dataBits in 7..8)
        require(!profile.txPolicy.defaultAllow) { "profiles must default deny" }
        if (profile.verification != "CONTROL_VERIFIED") require(profile.txPolicy.allowList.isEmpty())
        require("gas.open" !in profile.txPolicy.allowList)
        require(profile.txPolicy.allowList.none { it.startsWith("elevator.") })
    }
}

