package dev.wallpad.bridge.ota

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppRelease(val version: String, val code: Long, val url: String, val size: Int, val hash: String)
data class AppUpdateState(
    val busy: Boolean = false,
    val message: String = "앱 업데이트를 확인하세요.",
    val release: AppRelease? = null,
    val ready: Boolean = false,
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val github = GitHubReleaseRepository()
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private val context get() = getApplication<Application>()
    private val apk get() = File(context.cacheDir, "app-updates/manager.apk")
    private var waitingForInstallPermission = false
    @Suppress("DEPRECATION")
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { signature -> digest(signature.toByteArray()) }?.toSet() ?: emptySet()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun check(repository: String) {
        if (mutableState.value.busy) return
        mutableState.value = AppUpdateState(busy = true, message = "최신 APK 확인 중…")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val release = github.release(repository)
                    val assets = release.getJSONArray("assets")
                    fun asset(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .singleOrNull { it.getString("name") == name } ?: error("릴리스에 $name 파일이 없습니다.")
                    val manifest = JSONObject(github.fetch(asset("android-update.json").getString("browser_download_url"), 16384).decodeToString())
                    require(manifest.getInt("schema") == 1 && manifest.getString("packageName") == context.packageName)
                    val archive = asset("app-debug.apk")
                    val size = manifest.getInt("size")
                    require(size in 1..MAX_APK_SIZE && archive.getLong("size") == size.toLong())
                    val hash = manifest.getString("sha256")
                    require(hash.matches(Regex("[a-f0-9]{64}")))
                    AppRelease(manifest.getString("versionName"), manifest.getLong("versionCode"),
                        archive.getString("browser_download_url"), size, hash)
                }
            }.onSuccess { release ->
                val newer = release.code > code(installed())
                mutableState.value = AppUpdateState(
                    message = if (newer) "앱 ${release.version} 다운로드 가능" else "현재 앱이 최신 버전입니다.",
                    release = release.takeIf { newer },
                )
            }.onFailure { error -> mutableState.value = AppUpdateState(message = error.message ?: "조회 실패") }
        }
    }

    fun downloadAndInstall() {
        val release = mutableState.value.release ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, ready = false, message = "APK 다운로드 중…") }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val previous = apk.takeIf { it.isFile && it.length() == release.size.toLong() }?.readBytes()
                    val bytes = if (previous != null && digest(previous) == release.hash) previous
                        else github.fetch(release.url, release.size) { progress ->
                            mutableState.update { it.copy(message = "APK 다운로드 $progress%") }
                        }
                    require(bytes.size == release.size && digest(bytes) == release.hash) { "APK 크기/SHA-256 검증 실패" }
                    kotlin.check(apk.parentFile!!.mkdirs() || apk.parentFile!!.isDirectory)
                    apk.writeBytes(bytes)
                    verify(release)
                }
            }.onSuccess {
                mutableState.update { it.copy(ready = true, message = "APK 검증 완료 · 설치 화면 준비 중…") }
            }.onFailure { error ->
                apk.delete()
                mutableState.update { it.copy(ready = false, message = error.message ?: "다운로드 실패") }
            }
            mutableState.update { it.copy(busy = false) }
            if (result.isSuccess) install()
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(release: AppRelease) {
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            ?: error("APK를 읽을 수 없습니다.")
        val current = installed()
        require(candidate.packageName == context.packageName && code(candidate) == release.code && candidate.versionName == release.version) {
            "APK 패키지/버전이 일치하지 않습니다."
        }
        require(code(candidate) > code(current)) { "최신 앱이 이미 설치되어 있습니다." }
        val expected = signers(current)
        require(expected.isNotEmpty() && expected == signers(candidate)) { "현재 앱과 APK 서명 키가 다릅니다." }
        require(digest(apk.readBytes()) == release.hash) { "APK 무결성 검사 실패" }
    }

    fun install() {
        if (mutableState.value.busy || !mutableState.value.ready) return
        val release = mutableState.value.release ?: return
        runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutableState.update { it.copy(message = "이 출처 허용을 켜고 돌아오면 설치를 계속합니다.") }
                return
            }
            verify(release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            mutableState.update { it.copy(message = "Android 설치 화면에서 업데이트를 승인하세요. 취소하면 다시 설치할 수 있습니다.") }
        }.onFailure { error -> mutableState.update { it.copy(message = error.message ?: "설치 화면을 열지 못했습니다.") } }
    }

    fun resumePendingInstall() {
        if (!waitingForInstallPermission) return
        waitingForInstallPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    companion object { private const val MAX_APK_SIZE = 32 * 1024 * 1024 }
}
