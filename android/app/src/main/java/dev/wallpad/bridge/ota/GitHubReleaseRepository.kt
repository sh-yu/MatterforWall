package dev.wallpad.bridge.ota

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.io.File
import org.json.JSONObject

data class FirmwareRelease(val version: String, val imageUrl: String, val size: Int, val sha256: String)

/** Public GitHub releases only; credentials and signing keys never enter the app. */
class GitHubReleaseRepository {
    internal fun release(repository: String): JSONObject {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "owner/repository 형식으로 입력하세요." }
        val result = JSONObject(fetch("https://api.github.com/repos/$repository/releases/latest", 256 * 1024).decodeToString())
        require(!result.optBoolean("draft") && !result.optBoolean("prerelease"))
        return result
    }

    fun latest(repository: String): FirmwareRelease {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "owner/repository 형식으로 입력하세요." }
        val release = JSONObject(fetch("https://api.github.com/repos/$repository/releases/latest", 256 * 1024).decodeToString())
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
        val assets = release.getJSONArray("assets")
        val manifestAsset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .single { it.getString("name") == "firmware-update.json" }
        val manifest = JSONObject(fetch(manifestAsset.getString("browser_download_url"), 16384).decodeToString())
        require(manifest.getInt("schema") == 1 && manifest.getString("target") == "esp32c6")
        require(manifest.getString("project") == "wallpad-matter-bridge")
        val version = manifest.getString("version")
        require(release.getString("tag_name") == "v$version") { "릴리스 태그와 버전이 다릅니다." }
        val image = (0 until assets.length()).map { assets.getJSONObject(it) }
            .single { it.getString("name") == "wallpad-matter-bridge.bin" }
        val size = manifest.getInt("size")
        require(size in 4097..FirmwareImageValidator.MAX_IMAGE_SIZE && image.getLong("size") == size.toLong())
        val hash = manifest.getString("sha256")
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return FirmwareRelease(version, image.getString("browser_download_url"), size, hash)
    }

    fun download(release: FirmwareRelease, progress: (Int) -> Unit): ByteArray {
        val bytes = fetch(release.imageUrl, release.size, progress)
        require(bytes.size == release.size) { "다운로드 크기가 다릅니다." }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == release.sha256) { "SHA-256 검증 실패" }
        val info = FirmwareImageValidator.validate(bytes).getOrThrow()
        require(info.version == release.version) { "이미지와 릴리스 버전이 다릅니다." }
        return bytes
    }

    fun downloadCached(release: FirmwareRelease, directory: File, progress: (Int) -> Unit): ByteArray {
        require(release.sha256.matches(Regex("[a-f0-9]{64}")))
        require(directory.mkdirs() || directory.isDirectory)
        val cached = File(directory, "${release.sha256}.bin")
        if (cached.isFile && cached.length() == release.size.toLong()) {
            val bytes = cached.readBytes()
            val info = FirmwareImageValidator.validate(bytes).getOrNull()
            if (info?.sha256Hex == release.sha256 && info.version == release.version) {
                progress(100)
                return bytes
            }
        }
        val bytes = download(release, progress)
        val pending = File(directory, "firmware.part")
        try {
            pending.writeBytes(bytes)
            require(pending.renameTo(cached)) { "캐시 파일 저장 실패" }
            directory.listFiles()?.filter { it != cached && it.extension == "bin" }
                ?.forEach { it.delete() }
        } finally {
            pending.delete()
        }
        return bytes
    }

    internal fun fetch(address: String, limit: Int, progress: (Int) -> Unit = {}): ByteArray {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443))
            require(url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "허용되지 않은 다운로드 주소입니다."
            }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "WallpadBridgeManager")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("잘못된 리디렉션"))
                } else {
                    if (status == 404 && url.host == "api.github.com" && url.path.endsWith("/releases/latest")) {
                        error("게시된 업데이트가 없거나 저장소에 접근할 수 없습니다. 공개 저장소의 Releases에 업데이트가 게시된 뒤 다시 확인하세요.")
                    }
                    require(status == 200) { "GitHub 응답 오류 ($status)" }
                    require(connection.contentLengthLong <= limit) { "허용 크기를 초과했습니다." }
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= limit) { "허용 크기를 초과했습니다." }
                            output.write(buffer, 0, count)
                            progress((output.size().toLong() * 100 / limit).toInt())
                        }
                        output.toByteArray()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("리디렉션 횟수를 초과했습니다.")
    }
}
