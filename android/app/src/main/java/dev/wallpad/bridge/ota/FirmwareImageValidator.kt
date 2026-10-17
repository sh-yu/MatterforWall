package dev.wallpad.bridge.ota

import java.security.MessageDigest

data class FirmwareImageInfo(
    val version: String,
    val projectName: String,
    val sha256: ByteArray,
) {
    val sha256Hex: String = sha256.joinToString("") { "%02x".format(it) }
}

object FirmwareImageValidator {
    const val MAX_IMAGE_SIZE = 0x1e0000
    private const val APP_DESCRIPTION_OFFSET = 32
    private const val VERSION_OFFSET = APP_DESCRIPTION_OFFSET + 16
    private const val PROJECT_OFFSET = APP_DESCRIPTION_OFFSET + 48
    private const val TEXT_LENGTH = 32
    private const val SIGNATURE_SECTOR_SIZE = 4096
    private const val IMAGE_MAGIC = 0xe9
    private const val APP_DESCRIPTION_MAGIC = 0xabcd5432L
    private const val SIGNATURE_MAGIC = 0xe7
    private const val EXPECTED_PROJECT = "wallpad-matter-bridge"

    fun validate(image: ByteArray, runningVersion: String? = null): Result<FirmwareImageInfo> = runCatching {
        require(image.size in (SIGNATURE_SECTOR_SIZE + 1)..MAX_IMAGE_SIZE) { "OTA 슬롯 크기에 맞지 않는 파일입니다." }
        require(image[0].toInt() and 0xff == IMAGE_MAGIC) { "ESP 앱 이미지가 아닙니다." }
        require(readU32(image, APP_DESCRIPTION_OFFSET) == APP_DESCRIPTION_MAGIC) { "앱 설명 헤더가 손상되었습니다." }
        require(image[image.size - SIGNATURE_SECTOR_SIZE].toInt() and 0xff == SIGNATURE_MAGIC) {
            "서명 블록이 없는 펌웨어입니다."
        }
        val version = readText(image, VERSION_OFFSET)
        val project = readText(image, PROJECT_OFFSET)
        require(project == EXPECTED_PROJECT) { "다른 프로젝트의 펌웨어입니다." }
        val candidate = parseVersion(version) ?: error("펌웨어 버전 형식이 올바르지 않습니다.")
        parseVersion(runningVersion)?.let { current ->
            require(compareVersions(candidate, current) >= 0) { "현재 버전보다 오래된 펌웨어는 설치할 수 없습니다." }
        }
        FirmwareImageInfo(version, project, MessageDigest.getInstance("SHA-256").digest(image))
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readText(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + TEXT_LENGTH)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()

    private fun parseVersion(value: String?): List<Int>? {
        if (value == null || !value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
        return value.split('.').map(String::toInt)
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        for (index in 0..2) {
            if (left[index] != right[index]) return left[index].compareTo(right[index])
        }
        return 0
    }
}
