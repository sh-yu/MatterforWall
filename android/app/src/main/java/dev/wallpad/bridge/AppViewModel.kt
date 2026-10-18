package dev.wallpad.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wallpad.bridge.analysis.CapturedPacket
import dev.wallpad.bridge.analysis.PacketAnalyzer
import dev.wallpad.bridge.analysis.PacketGroup
import dev.wallpad.bridge.protocol.BleFrame
import dev.wallpad.bridge.protocol.BleFrameCodec
import dev.wallpad.bridge.protocol.BleFrameReassembler
import dev.wallpad.bridge.protocol.MessageType
import dev.wallpad.bridge.ota.FirmwareImageInfo
import dev.wallpad.bridge.ota.FirmwareImageValidator
import dev.wallpad.bridge.transport.ConnectionState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DiscoveredBridge(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class DashboardState(
    val learnedLights: List<dev.wallpad.bridge.data.LearnedLightDraft> = emptyList(),
    val learning: dev.wallpad.bridge.analysis.GuidedLearningState = dev.wallpad.bridge.analysis.GuidedLearningState(),
    val releaseRepository: String = "",
    val releaseMessage: String = "GitHub 저장소를 입력하세요.",
    val releaseBusy: Boolean = false,
    val availableRelease: dev.wallpad.bridge.ota.FirmwareRelease? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val discoveredDevices: List<DiscoveredBridge> = emptyList(),
    val statusMessage: String = "ESP 브리지를 검색해 연결하세요.",
    val packets: List<CapturedPacket> = emptyList(),
    val groups: List<PacketGroup> = emptyList(),
    val paused: Boolean = false,
    val firmwareVersion: String = "확인되지 않음",
    val operatingMode: String = "확인되지 않음",
    val matter: String = "확인되지 않음",
    val matterManualCode: String? = null,
    val thread: String = "확인되지 않음",
    val rs485Errors: Long = 0,
    val rxFrames: Long = 0,
    val rxBytes: Long = 0,
    val freeHeap: Long? = null,
    val minimumFreeHeap: Long? = null,
    val lastPacketAtMillis: Long? = null,
    val firmwareFileName: String? = null,
    val firmwareCandidateVersion: String? = null,
    val firmwareCandidateSize: Int = 0,
    val firmwareSha256: String? = null,
    val otaState: FirmwareUpdateState = FirmwareUpdateState.IDLE,
    val otaProgress: Int = 0,
    val otaMessage: String = "서명된 펌웨어 파일을 선택하세요.",
)

enum class FirmwareUpdateState { IDLE, FILE_READY, STARTING, UPLOADING, VERIFYING, REBOOTING, COMPLETE, ERROR }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val releasePreferences = application.getSharedPreferences("updates", 0)
    private val releaseRepository = dev.wallpad.bridge.ota.GitHubReleaseRepository()
    private val analyzer = PacketAnalyzer()
    private val reassembler = BleFrameReassembler()
    private val bluetoothManager = application.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(DashboardState(
        releaseRepository = releasePreferences.getString("repository", null)
            ?.takeIf { it.isNotBlank() } ?: "sh-yu/MatterforWall",
    ))
    val state: StateFlow<DashboardState> = mutableState.asStateFlow()

    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var scannerCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var negotiatedMtu = 23
    private var selectedFirmware: ByteArray? = null
    private var selectedFirmwareInfo: FirmwareImageInfo? = null
    private var otaSession: OtaSession? = null
    private var writeKind: WriteKind? = null
    private var sequence = 1
    private var pendingReconnectAddress: String? = null
    private var awaitingOtaVersion: String? = null
    private var beginAccepted = false
    private var queryResolved = false
    private var learnedLightsLoaded = false

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val file = java.io.File(getApplication<Application>().filesDir, "learned-lights.json")
                runCatching { if (file.exists()) dev.wallpad.bridge.data.LearnedLightDrafts.decode(file.readText()) else emptyList() }
            }
            saved.onSuccess { drafts ->
                mutableState.update { it.copy(learnedLights = drafts) }
                learnedLightsLoaded = true
            }
                .onFailure { mutableState.update { state -> state.copy(statusMessage = "저장된 학습 파일을 읽지 못했습니다. 원본 파일을 보존합니다.") } }
        }
        if (mutableState.value.releaseRepository.isNotBlank()) checkRelease()
    }

    private data class OtaSession(
        val image: ByteArray,
        var offset: Int = 0,
        var lastChunkSize: Int = 0,
        var retries: Int = 0,
    )
    private enum class WriteKind { BEGIN, DATA, FINISH, ABORT, QUERY }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasBluetoothPermission()) {
            mutableState.update {
                it.copy(connection = ConnectionState.ERROR, statusMessage = "블루투스 권한이 필요합니다.")
            }
            return
        }
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            mutableState.update {
                it.copy(connection = ConnectionState.ERROR, statusMessage = "휴대폰의 블루투스를 켜 주세요.")
            }
            return
        }

        stopScan(updateState = false)
        mutableState.update {
            it.copy(
                connection = ConnectionState.SCANNING,
                discoveredDevices = emptyList(),
                statusMessage = "주변의 Wallpad Bridge를 찾는 중입니다…",
            )
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = acceptScanResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::acceptScanResult)

            override fun onScanFailed(errorCode: Int) {
                scannerCallback = null
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.ERROR,
                        statusMessage = "BLE 검색에 실패했습니다. (오류 $errorCode)",
                    )
                }
            }
        }
        scannerCallback = callback
        scanner.startScan(callback)
        handler.postDelayed({ stopScan(updateState = true) }, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun acceptScanResult(result: ScanResult) {
        val advertisedService = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        val name = result.device.name ?: result.scanRecord?.deviceName
        if (!advertisedService && name?.contains("wallpad", ignoreCase = true) != true &&
            name?.contains("WMB", ignoreCase = true) != true
        ) return

        val bridge = DiscoveredBridge(name ?: "Wallpad Bridge", result.device.address, result.rssi)
        mutableState.update { current ->
            val devices = (current.discoveredDevices.filterNot { it.address == bridge.address } + bridge)
                .sortedByDescending { it.rssi }
            current.copy(discoveredDevices = devices, statusMessage = "${devices.size}개 브리지를 찾았습니다.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(updateState: Boolean) {
        scannerCallback?.let { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(it) }
        scannerCallback = null
        if (updateState && mutableState.value.connection == ConnectionState.SCANNING) {
            mutableState.update { current ->
                current.copy(
                    connection = ConnectionState.DISCONNECTED,
                    statusMessage = if (current.discoveredDevices.isEmpty()) {
                        "검색된 ESP 브리지가 없습니다. ESP의 BLE 서비스 상태를 확인하세요."
                    } else {
                        "연결할 브리지를 선택하세요."
                    },
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasBluetoothPermission()) return
        stopScan(updateState = false)
        bluetoothGatt?.close()
        descriptorQueue.clear()
        reassembler.reset()
        val device = bluetoothManager?.adapter?.getRemoteDevice(address) ?: return
        mutableState.update {
            it.copy(
                connection = ConnectionState.CONNECTING,
                deviceName = it.discoveredDevices.firstOrNull { item -> item.address == address }?.name,
                deviceAddress = address,
                statusMessage = "ESP 브리지에 연결하는 중입니다…",
            )
        }
        bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        descriptorQueue.clear()
        reassembler.reset()
        mutableState.update {
            DashboardState(
                releaseRepository = it.releaseRepository,
                releaseMessage = it.releaseMessage,
                availableRelease = it.availableRelease,
                releaseBusy = it.releaseBusy,
                discoveredDevices = it.discoveredDevices,
                statusMessage = "연결을 해제했습니다.",
            )
        }
    }

    private fun updateBusy(): Boolean = mutableState.value.otaState in setOf(
        FirmwareUpdateState.STARTING, FirmwareUpdateState.UPLOADING,
        FirmwareUpdateState.VERIFYING, FirmwareUpdateState.REBOOTING,
    )

    fun setReleaseRepository(value: String) {
        if (mutableState.value.releaseBusy || updateBusy()) return
        releasePreferences.edit().putString("repository", value.trim()).apply()
        mutableState.update { it.copy(releaseRepository = value.trim(), availableRelease = null) }
    }

    fun checkRelease() {
        if (mutableState.value.releaseBusy || updateBusy()) return
        val repository = mutableState.value.releaseRepository
        mutableState.update { it.copy(releaseBusy = true, availableRelease = null, releaseMessage = "최신 릴리스 조회 중…") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { releaseRepository.latest(repository) } }
                .onSuccess { release ->
                    val current = mutableState.value.firmwareVersion
                    val newer = dev.wallpad.bridge.ota.ReleaseVersion.isNewer(release.version, current)
                    mutableState.update { it.copy(
                        availableRelease = if (newer) release else null,
                        releaseMessage = if (newer) "${release.version} 다운로드 가능" else "최신 버전입니다.",
                    ) }
                }.onFailure { error -> mutableState.update { it.copy(releaseMessage = error.message ?: "조회 실패") } }
            mutableState.update { it.copy(releaseBusy = false) }
        }
    }

    fun downloadRelease() {
        if (mutableState.value.releaseBusy || updateBusy()) return
        if (mutableState.value.connection != ConnectionState.READY) {
            mutableState.update { it.copy(releaseMessage = "ESP에 연결한 뒤 다운로드 및 업데이트를 누르세요.") }
            return
        }
        val release = mutableState.value.availableRelease ?: return
        mutableState.update { it.copy(releaseBusy = true, releaseMessage = "다운로드 중…") }
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    releaseRepository.downloadCached(release,
                        java.io.File(getApplication<Application>().cacheDir, "firmware-updates")) { percent ->
                        mutableState.update { it.copy(releaseMessage = "다운로드 $percent%") }
                    }
                }
                val info = FirmwareImageValidator.validate(bytes, mutableState.value.firmwareVersion).getOrThrow()
                selectedFirmware = bytes
                selectedFirmwareInfo = info
                mutableState.update { it.copy(
                    firmwareFileName = "wallpad-matter-bridge.bin",
                    firmwareCandidateVersion = info.version, firmwareCandidateSize = bytes.size,
                    firmwareSha256 = info.sha256Hex, otaState = FirmwareUpdateState.FILE_READY,
                    otaProgress = 0, otaMessage = "캐시·SHA-256 확인 완료 · OTA 준비 중…",
                    releaseMessage = "다운로드 완료 · ${info.version}",
                ) }
            }.onFailure { error -> mutableState.update { it.copy(releaseMessage = error.message ?: "다운로드 실패") } }
            mutableState.update { it.copy(releaseBusy = false) }
            if (result.isSuccess && mutableState.value.otaState == FirmwareUpdateState.FILE_READY &&
                selectedFirmwareInfo?.version == release.version) startFirmwareUpdate()
        }
    }

    fun selectFirmware(uri: Uri) {
        if (mutableState.value.releaseBusy) return
        if (mutableState.value.otaState in setOf(
                FirmwareUpdateState.STARTING,
                FirmwareUpdateState.UPLOADING,
                FirmwareUpdateState.VERIFYING,
                FirmwareUpdateState.REBOOTING,
            )
        ) return
        mutableState.update { it.copy(otaState = FirmwareUpdateState.IDLE, otaMessage = "펌웨어를 검사하는 중입니다…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "firmware.bin"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= FirmwareImageValidator.MAX_IMAGE_SIZE) { "OTA 슬롯보다 큰 파일입니다." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("선택한 파일을 열 수 없습니다.")
                    Triple(name, bytes, FirmwareImageValidator.validate(bytes, mutableState.value.firmwareVersion).getOrThrow())
                }
            }.onSuccess { (name, bytes, info) ->
                selectedFirmware = bytes
                selectedFirmwareInfo = info
                mutableState.update {
                    it.copy(
                        firmwareFileName = name,
                        firmwareCandidateVersion = info.version,
                        firmwareCandidateSize = bytes.size,
                        firmwareSha256 = info.sha256Hex,
                        otaState = FirmwareUpdateState.FILE_READY,
                        otaProgress = 0,
                        otaMessage = "서명 이미지 확인 완료 · 업데이트할 수 있습니다.",
                    )
                }
            }.onFailure { error ->
                selectedFirmware = null
                selectedFirmwareInfo = null
                mutableState.update {
                    it.copy(otaState = FirmwareUpdateState.ERROR, otaMessage = error.message ?: "파일 검사에 실패했습니다.")
                }
            }
        }
    }

    fun startFirmwareUpdate() {
        if (mutableState.value.releaseBusy || updateBusy()) return
        val image = selectedFirmware
        val info = selectedFirmwareInfo
        if (image == null || info == null) {
            failOta("먼저 서명된 펌웨어 파일을 선택하세요.")
            return
        }
        if (mutableState.value.connection != ConnectionState.READY) {
            failOta("ESP 브리지에 먼저 연결하세요.")
            return
        }
        // Recheck against the connected device, which may differ from the one
        // present when the release was downloaded.
        val validation = FirmwareImageValidator.validate(image, mutableState.value.firmwareVersion)
        if (validation.isFailure) {
            failOta(validation.exceptionOrNull()?.message ?: "펌웨어 재검사 실패")
            return
        }
        requestOtaConnectionPriority()
        otaSession = OtaSession(image)
        awaitingOtaVersion = info.version
        pendingReconnectAddress = mutableState.value.deviceAddress
        beginAccepted = false
        queryResolved = false
        mutableState.update {
            it.copy(otaState = FirmwareUpdateState.STARTING, otaProgress = 0, otaMessage = "ESP에 업데이트 시작을 요청하는 중입니다…")
        }
        val payload = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN)
            .put(OTA_BEGIN.toByte()).putInt(image.size).put(info.sha256).array()
        if (!writeOta(OTA_CONTROL_UUID, MessageType.OTA_CONTROL, payload, WriteKind.BEGIN)) {
            failOta("OTA 시작 요청을 전송하지 못했습니다.")
        }
    }

    fun abortFirmwareUpdate() {
        val active = mutableState.value.otaState in setOf(
            FirmwareUpdateState.STARTING,
            FirmwareUpdateState.UPLOADING,
            FirmwareUpdateState.VERIFYING,
        )
        if (active) {
            writeOta(OTA_CONTROL_UUID, MessageType.OTA_CONTROL, byteArrayOf(OTA_ABORT.toByte()), WriteKind.ABORT)
        }
        otaSession = null
        awaitingOtaVersion = null
        pendingReconnectAddress = null
        beginAccepted = false
        mutableState.update { it.copy(otaState = FirmwareUpdateState.FILE_READY, otaProgress = 0, otaMessage = "업데이트를 취소했습니다.") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mutableState.update {
                        it.copy(
                            connection = ConnectionState.AUTHENTICATING,
                            statusMessage = "연결됨 · 보안 채널과 MTU를 준비하는 중입니다…",
                        )
                    }
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val beforeDisconnect = mutableState.value
                    val rebooting = beforeDisconnect.otaState == FirmwareUpdateState.REBOOTING ||
                        (beforeDisconnect.otaState == FirmwareUpdateState.VERIFYING &&
                            beforeDisconnect.otaProgress == 100)
                    val reconnectAddress = pendingReconnectAddress ?: beforeDisconnect.deviceAddress
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    descriptorQueue.clear()
                    reassembler.reset()
                    otaSession = null
                    writeKind = null
                    beginAccepted = false
                    queryResolved = false
                    mutableState.update {
                        it.copy(
                            connection = if (status == BluetoothGatt.GATT_SUCCESS) {
                                ConnectionState.DISCONNECTED
                            } else {
                                ConnectionState.ERROR
                            },
                            statusMessage = if (rebooting) {
                                "업데이트 후 ESP가 재부팅되었습니다. 다시 연결하는 중입니다…"
                            } else if (status == BluetoothGatt.GATT_SUCCESS) {
                                "ESP 브리지 연결이 해제되었습니다."
                            } else {
                                "연결에 실패했습니다. (GATT $status)"
                            },
                            deviceAddress = null,
                            otaState = when {
                                rebooting -> FirmwareUpdateState.REBOOTING
                                it.otaState in setOf(FirmwareUpdateState.STARTING, FirmwareUpdateState.UPLOADING, FirmwareUpdateState.VERIFYING) -> FirmwareUpdateState.ERROR
                                else -> it.otaState
                            },
                            otaProgress = if (rebooting) 100 else it.otaProgress,
                            otaMessage = when {
                                rebooting -> "ESP 재부팅 후 설치 버전을 확인하기 위해 자동 재연결 중입니다…"
                                it.otaState in setOf(FirmwareUpdateState.STARTING, FirmwareUpdateState.UPLOADING, FirmwareUpdateState.VERIFYING) -> "전송 중 BLE 연결이 끊겼습니다. 다시 연결한 뒤 재시도하세요."
                                else -> it.otaMessage
                            },
                        )
                    }
                    if (rebooting && reconnectAddress != null) {
                        pendingReconnectAddress = null
                        handler.postDelayed({ connect(reconnectAddress) }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.ERROR,
                        statusMessage = "연결은 됐지만 Wallpad 관리 서비스를 찾지 못했습니다.",
                    )
                }
                return
            }

            descriptorQueue.clear()
            queueSubscription(gatt, service.getCharacteristic(PACKET_STREAM_UUID), indication = false)
            queueSubscription(gatt, service.getCharacteristic(DIAGNOSTICS_UUID), indication = false)
            queueSubscription(gatt, service.getCharacteristic(RESPONSE_UUID), indication = true)
            queueSubscription(gatt, service.getCharacteristic(OTA_CONTROL_UUID), indication = false)
            if (descriptorQueue.isEmpty()) {
                finishGattSetup(gatt, service)
            } else {
                writeNextDescriptor(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                descriptorQueue.clear()
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.ERROR,
                        statusMessage = "BLE 알림 설정에 실패했습니다. (GATT $status)",
                    )
                }
                return
            }
            if (descriptorQueue.isNotEmpty()) descriptorQueue.removeFirst()
            if (descriptorQueue.isNotEmpty()) {
                writeNextDescriptor(gatt)
            } else {
                gatt.getService(SERVICE_UUID)?.let { finishGattSetup(gatt, it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(gatt, characteristic.uuid, value, status)
        }

        @Deprecated("Android 13 이전 콜백")
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleEncodedFrame(value)
        }

        @Deprecated("Android 13 이전 콜백")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleEncodedFrame(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val completed = writeKind ?: return
            writeKind = null
            if (completed == WriteKind.DATA) {
                // Samsung devices can report GATT 6 even after the peripheral
                // committed the write. The ESP's OTA_DATA ACK is authoritative.
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (completed == WriteKind.FINISH &&
                    mutableState.value.otaState == FirmwareUpdateState.VERIFYING
                ) {
                    mutableState.update {
                        it.copy(otaMessage = "검증 요청 응답이 지연되고 있습니다. ESP의 재부팅과 설치 버전을 확인합니다…")
                    }
                    handler.postDelayed({
                        if (mutableState.value.otaState == FirmwareUpdateState.VERIFYING) {
                            failOta("ESP의 펌웨어 검증 결과를 받지 못했습니다. 다시 연결해 현재 버전을 확인하세요.")
                        }
                    }, OTA_FINISH_TIMEOUT_MS)
                    return
                }
                failOta("BLE 쓰기에 실패했습니다. (GATT $status)")
                return
            }
            when (completed) {
                WriteKind.BEGIN -> if (beginAccepted) sendNextOtaChunk()
                WriteKind.DATA -> Unit
                WriteKind.QUERY -> if (queryResolved) continueAfterQuery()
                WriteKind.FINISH, WriteKind.ABORT -> Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun queueSubscription(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        indication: Boolean,
    ) {
        if (characteristic == null || !gatt.setCharacteristicNotification(characteristic, true)) return
        characteristic.getDescriptor(CCCD_UUID)?.let {
            descriptorQueue += it to if (indication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor(gatt: BluetoothGatt) {
        val (descriptor, value) = descriptorQueue.firstOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishGattSetup(gatt: BluetoothGatt, service: BluetoothGattService) {
        mutableState.update {
            it.copy(
                connection = ConnectionState.READY,
                statusMessage = "ESP 관리 서비스와 연결되었습니다.",
            )
        }
        service.getCharacteristic(DEVICE_INFO_UUID)?.let(gatt::readCharacteristic)
    }

    @SuppressLint("MissingPermission")
    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        uuid: UUID,
        value: ByteArray,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) handleEncodedFrame(value)
        if (uuid == DEVICE_INFO_UUID) {
            gatt.getService(SERVICE_UUID)?.getCharacteristic(DIAGNOSTICS_UUID)?.let(gatt::readCharacteristic)
        } else if (uuid == DIAGNOSTICS_UUID && mutableState.value.matter == "enabled") {
            gatt.getService(SERVICE_UUID)?.getCharacteristic(CONFIGURATION_UUID)?.let(gatt::readCharacteristic)
        }
    }

    private fun handleEncodedFrame(encoded: ByteArray) {
        val decoded = BleFrameCodec.decode(encoded) ?: return
        val frame = reassembler.accept(decoded) ?: return
        when (frame.type) {
            MessageType.DEVICE_INFO -> parseDeviceInfo(frame)
            MessageType.CONFIGURATION -> runCatching { JSONObject(frame.payload.decodeToString()) }.onSuccess { json ->
                // Onboarding code is only held in UI memory, never exported/logged.
                mutableState.update { it.copy(matterManualCode = json.optString("matterManualCode").takeIf(String::isNotBlank)) }
            }
            MessageType.DIAGNOSTICS -> parseDiagnostics(frame)
            MessageType.PACKET_STREAM -> parsePacket(frame)
            MessageType.OTA_CONTROL -> handleOtaStatus(frame)
            MessageType.ERROR -> mutableState.update {
                it.copy(statusMessage = "ESP 응답: ${frame.payload.decodeToString()}")
            }
            else -> Unit
        }
    }

    private fun handleOtaStatus(frame: BleFrame) {
        if (frame.payload.size < 10) {
            failOta("ESP의 OTA 응답 형식이 올바르지 않습니다.")
            return
        }
        val input = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val operation = input.get().toInt() and 0xff
        val status = input.get().toInt() and 0xff
        val nextOffset = input.int
        val expectedSize = input.int
        val detail = ByteArray(input.remaining()).also(input::get).decodeToString()
        if (status != OTA_OK) {
            failOta("ESP가 업데이트를 거부했습니다: $detail (코드 $status, 오프셋 $nextOffset)")
            return
        }
        when (operation) {
            OTA_BEGIN -> {
                val session = otaSession ?: return
                if (nextOffset != 0 || expectedSize != session.image.size) {
                    failOta("ESP와 펌웨어 크기/오프셋이 일치하지 않습니다.")
                    return
                }
                beginAccepted = true
                mutableState.update { it.copy(otaState = FirmwareUpdateState.UPLOADING, otaMessage = "펌웨어 전송을 시작합니다…") }
                if (writeKind == null) sendNextOtaChunk()
            }
            OTA_FINISH -> {
                pendingReconnectAddress = mutableState.value.deviceAddress
                mutableState.update {
                    it.copy(
                        otaState = FirmwareUpdateState.REBOOTING,
                        otaProgress = 100,
                        otaMessage = "서명과 SHA-256 검증 완료 · ESP가 재부팅됩니다…",
                    )
                }
            }
            OTA_ABORT -> {
                otaSession = null
                mutableState.update { it.copy(otaState = FirmwareUpdateState.FILE_READY, otaProgress = 0, otaMessage = "업데이트가 취소되었습니다.") }
            }
            OTA_DATA_ACK -> {
                val session = otaSession ?: return
                if (expectedSize != session.image.size || nextOffset <= session.offset || nextOffset > session.image.size) {
                    failOta("ESP의 청크 ACK 오프셋이 올바르지 않습니다.")
                    return
                }
                writeKind = null
                session.offset = nextOffset
                session.retries = 0
                val progress = (session.offset.toLong() * 100L / session.image.size).toInt()
                mutableState.update { it.copy(otaProgress = progress, otaMessage = "펌웨어 전송 중 · $progress%") }
                if (session.offset >= session.image.size) sendOtaFinish()
                else handler.postDelayed(::sendNextOtaChunk, CHUNK_INTERVAL_MS)
            }
            OTA_QUERY -> {
                val session = otaSession ?: return
                if (expectedSize != session.image.size || nextOffset < session.offset || nextOffset > session.image.size) {
                    failOta("ESP의 OTA 오프셋 응답이 올바르지 않습니다.")
                    return
                }
                if (nextOffset > session.offset) {
                    session.offset = nextOffset
                    session.retries = 0
                    val progress = (session.offset.toLong() * 100L / session.image.size).toInt()
                    mutableState.update { it.copy(otaProgress = progress, otaMessage = "펌웨어 전송 중 · $progress%") }
                } else {
                    session.retries++
                    if (session.retries > MAX_CHUNK_RETRIES) {
                        failOta("ESP가 같은 OTA 청크를 반복해서 확인하지 못했습니다.")
                        return
                    }
                }
                queryResolved = true
                if (writeKind == null) continueAfterQuery()
            }
        }
    }

    private fun continueAfterQuery() {
        queryResolved = false
        val session = otaSession ?: return
        if (session.offset >= session.image.size) sendOtaFinish()
        else handler.postDelayed(::sendNextOtaChunk, CHUNK_INTERVAL_MS)
    }

    private fun queryOtaOffset() {
        queryResolved = false
        if (!writeOta(
                OTA_CONTROL_UUID,
                MessageType.OTA_CONTROL,
                byteArrayOf(OTA_QUERY.toByte()),
                WriteKind.QUERY,
            )
        ) {
            failOta("ESP의 OTA 오프셋을 확인하지 못했습니다.")
        }
    }

    private fun sendNextOtaChunk() {
        val session = otaSession ?: return
        if (writeKind != null) return
        val maxChunk = dev.wallpad.bridge.ota.OtaTransferSizing.chunkSize(negotiatedMtu)
        val chunkSize = minOf(maxChunk, session.image.size - session.offset)
        if (chunkSize <= 0) {
            sendOtaFinish()
            return
        }
        session.lastChunkSize = chunkSize
        val payload = ByteBuffer.allocate(4 + chunkSize).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(session.offset)
            .put(session.image, session.offset, chunkSize)
            .array()
        val offsetAtSend = session.offset
        if (!writeOta(OTA_DATA_UUID, MessageType.OTA_DATA, payload, WriteKind.DATA, withoutResponse = true)) {
            failOta("펌웨어 청크를 전송하지 못했습니다. (오프셋 ${session.offset})")
        } else {
            handler.postDelayed({
                val current = otaSession
                if (current != null && current.offset == offsetAtSend &&
                    mutableState.value.otaState == FirmwareUpdateState.UPLOADING && writeKind == null
                ) queryOtaOffset()
            }, OTA_ACK_TIMEOUT_MS)
        }
    }

    private fun sendOtaFinish() {
        mutableState.update { it.copy(otaState = FirmwareUpdateState.VERIFYING, otaProgress = 100, otaMessage = "ESP에서 서명과 SHA-256을 검증하는 중입니다…") }
        if (!writeOta(
                OTA_CONTROL_UUID,
                MessageType.OTA_CONTROL,
                byteArrayOf(OTA_FINISH.toByte()),
                WriteKind.FINISH,
            )
        ) {
            failOta("검증 요청을 전송하지 못했습니다.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeOta(
        uuid: UUID,
        type: MessageType,
        payload: ByteArray,
        kind: WriteKind,
        withoutResponse: Boolean = false,
    ): Boolean {
        if (writeKind != null) return false
        val gatt = bluetoothGatt ?: return false
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(uuid) ?: return false
        val frame = BleFrame(type = type, sequence = (sequence++ and 0xffff).toUShort(), payload = payload)
        val value = BleFrameCodec.encode(frame)
        writeKind = kind
        val writeType = if (withoutResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!queued || withoutResponse) writeKind = null
        return queued
    }

    @SuppressLint("MissingPermission")
    private fun requestOtaConnectionPriority() {
        bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    private fun failOta(message: String) {
        otaSession = null
        writeKind = null
        awaitingOtaVersion = null
        pendingReconnectAddress = null
        beginAccepted = false
        queryResolved = false
        mutableState.update { it.copy(otaState = FirmwareUpdateState.ERROR, otaMessage = message) }
    }

    private fun parseDeviceInfo(frame: BleFrame) {
        runCatching { JSONObject(frame.payload.decodeToString()) }.onSuccess { json ->
            val installedVersion = json.optString("firmware", "확인되지 않음")
            val expectedVersion = awaitingOtaVersion
            mutableState.update {
                it.copy(
                    deviceName = json.optString("name", it.deviceName ?: "Wallpad Bridge"),
                    firmwareVersion = installedVersion,
                    matter = json.optString("matter", it.matter),
                    thread = json.optString("thread", it.thread),
                    availableRelease = it.availableRelease?.takeIf { release ->
                        dev.wallpad.bridge.ota.ReleaseVersion.isNewer(release.version, installedVersion)
                    },
                    releaseMessage = if (it.availableRelease != null &&
                        !dev.wallpad.bridge.ota.ReleaseVersion.isNewer(it.availableRelease.version, installedVersion))
                        "현재 펌웨어가 최신 버전입니다." else it.releaseMessage,
                    operatingMode = json.optString("mode", "확인되지 않음"),
                    otaState = when {
                        it.otaState == FirmwareUpdateState.REBOOTING && expectedVersion == installedVersion ->
                            FirmwareUpdateState.COMPLETE
                        it.otaState == FirmwareUpdateState.REBOOTING && expectedVersion != null ->
                            FirmwareUpdateState.ERROR
                        else -> it.otaState
                    },
                    otaMessage = when {
                        it.otaState == FirmwareUpdateState.REBOOTING && expectedVersion == installedVersion ->
                            "OTA 설치 확인 완료 · 현재 펌웨어 $installedVersion"
                        it.otaState == FirmwareUpdateState.REBOOTING && expectedVersion != null ->
                            "설치 버전 불일치 · 예상 $expectedVersion, 현재 $installedVersion"
                        else -> it.otaMessage
                    },
                )
            }
            if (expectedVersion != null && mutableState.value.otaState in
                setOf(FirmwareUpdateState.COMPLETE, FirmwareUpdateState.ERROR)
            ) {
                awaitingOtaVersion = null
                pendingReconnectAddress = null
            }
        }
    }

    private fun parseDiagnostics(frame: BleFrame) {
        runCatching { JSONObject(frame.payload.decodeToString()) }.onSuccess { json ->
            val errors = json.optLong("framingErrors") + json.optLong("parityErrors") +
                json.optLong("bufferOverruns")
            mutableState.update {
                it.copy(
                    freeHeap = json.optLong("freeHeap"),
                    minimumFreeHeap = json.optLong("minimumFreeHeap"),
                    rxFrames = json.optLong("rxFrames"),
                    rxBytes = json.optLong("rxBytes"),
                    rs485Errors = errors,
                )
            }
        }
    }

    private fun parsePacket(frame: BleFrame) {
        if (frame.payload.size < PACKET_PREFIX_SIZE) return
        val input = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val timestampMicros = input.long
        val direction = if (input.get().toInt() == 0) {
            CapturedPacket.Direction.RX
        } else {
            CapturedPacket.Direction.TX
        }
        val synthetic = input.get().toInt() != 0
        val bytes = ByteArray(input.remaining()).also(input::get)
        val packet = CapturedPacket(timestampMicros, direction, bytes, synthetic)
        mutableState.update { current ->
            val learning = current.learning
            current.copy(learning = when (learning.phase) {
                dev.wallpad.bridge.analysis.LearningPhase.BASELINE -> learning.copy(
                    overflow = learning.overflow || learning.baseline.size >= dev.wallpad.bridge.analysis.GuidedLearning.MAX_FRAMES,
                    baseline = (learning.baseline + packet).takeLast(dev.wallpad.bridge.analysis.GuidedLearning.MAX_FRAMES),
                )
                dev.wallpad.bridge.analysis.LearningPhase.ACTION -> learning.copy(
                    overflow = learning.overflow || learning.actionPackets.size >= dev.wallpad.bridge.analysis.GuidedLearning.MAX_FRAMES,
                    actionPackets = (learning.actionPackets + packet).takeLast(dev.wallpad.bridge.analysis.GuidedLearning.MAX_FRAMES),
                )
                else -> learning
            })
        }
        mutableState.update { current ->
            if (current.paused) current else {
                val packets = (current.packets + packet).takeLast(MAX_PACKETS)
                current.copy(
                    packets = packets,
                    groups = analyzer.group(packets),
                    rxFrames = maxOf(current.rxFrames, packets.size.toLong()),
                    lastPacketAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    fun togglePause() = mutableState.update { it.copy(paused = !it.paused) }

    private val learningSaveMutex = kotlinx.coroutines.sync.Mutex()

    fun saveLearnedLight(name: String, hex: String) {
        if (!learnedLightsLoaded) {
            mutableState.update { it.copy(learning = it.learning.copy(message = "저장 파일 로딩을 완료하지 못해 덮어쓰지 않습니다.")) }
            return
        }
        val snapshot = mutableState.value
        val draft = runCatching { dev.wallpad.bridge.data.LearnedLightDrafts.create(
            snapshot.deviceAddress ?: "", name, snapshot.learning, hex,
        ) }.getOrElse {
            mutableState.update { state -> state.copy(learning = state.learning.copy(message = "장치 이름·연결·학습 후보를 확인하세요.")) }
            return
        }
        viewModelScope.launch {
            learningSaveMutex.lock()
            try {
                val drafts = mutableState.value.learnedLights.filterNot {
                    it.bridgeAddress == draft.bridgeAddress && it.name == draft.name && it.action == draft.action
                } + draft
                withContext(Dispatchers.IO) {
                    val encoded = dev.wallpad.bridge.data.LearnedLightDrafts.encode(drafts)
                    val file = android.util.AtomicFile(java.io.File(getApplication<Application>().filesDir, "learned-lights.json"))
                    val stream = file.startWrite()
                    try { stream.write(encoded.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                    catch (error: Exception) { file.failWrite(stream); throw error }
                }
                mutableState.update { it.copy(learnedLights = drafts, learning = it.learning.copy(message = "학습 초안 저장 완료. 검증 전이며 송신 권한은 부여되지 않습니다.")) }
            } catch (error: Exception) {
                mutableState.update { it.copy(learning = it.learning.copy(message = "저장 실패: 최대 32개 동작 또는 저장 공간을 확인하세요.")) }
            } finally { learningSaveMutex.unlock() }
        }
    }

    private var learningJob: kotlinx.coroutines.Job? = null

    fun resetLearning(device: String, action: String) {
        learningJob?.cancel()
        mutableState.update { it.copy(learning = dev.wallpad.bridge.analysis.GuidedLearningState(device, action)) }
    }

    fun startLearningRound() {
        val current = mutableState.value
        if (current.connection != ConnectionState.READY || otaSession != null || learningJob?.isActive == true) return
        learningJob = viewModelScope.launch {
            mutableState.update { it.copy(learning = it.learning.copy(
                phase = dev.wallpad.bridge.analysis.LearningPhase.BASELINE,
                baseline = emptyList(), actionPackets = emptyList(), candidates = emptyList(), overflow = false,
                message = "조작하지 마세요. 평상시 트래픽을 수집합니다.",
            )) }
            for (second in 5 downTo 1) {
                mutableState.update { it.copy(learning = it.learning.copy(secondsLeft = second)) }
                kotlinx.coroutines.delay(1000)
                if (mutableState.value.connection != ConnectionState.READY || otaSession != null) {
                    mutableState.update { it.copy(learning = it.learning.copy(phase = dev.wallpad.bridge.analysis.LearningPhase.READY, secondsLeft = 0, message = "연결 중단 또는 OTA: 이번 회차를 폐기했습니다.")) }
                    return@launch
                }
            }
            mutableState.update { it.copy(learning = it.learning.copy(
                phase = dev.wallpad.bridge.analysis.LearningPhase.ACTION,
                message = "지금 ${it.learning.device}을(를) ${it.learning.action} 조작하세요. 같은 동작만 1회 하세요.",
            )) }
            for (second in 10 downTo 1) {
                mutableState.update { it.copy(learning = it.learning.copy(secondsLeft = second)) }
                kotlinx.coroutines.delay(1000)
                if (mutableState.value.connection != ConnectionState.READY || otaSession != null) {
                    mutableState.update { it.copy(learning = it.learning.copy(phase = dev.wallpad.bridge.analysis.LearningPhase.READY, secondsLeft = 0, message = "연결 중단 또는 OTA: 이번 회차를 폐기했습니다.")) }
                    return@launch
                }
            }
            mutableState.update {
                val learning = it.learning
                val rounds = if (!learning.overflow && learning.baseline.isNotEmpty() && learning.actionPackets.isNotEmpty()) {
                    (learning.rounds + dev.wallpad.bridge.analysis.LearningRound(learning.baseline, learning.actionPackets)).takeLast(5)
                } else learning.rounds
                it.copy(learning = learning.copy(
                    phase = dev.wallpad.bridge.analysis.LearningPhase.REVIEW, secondsLeft = 0,
                    rounds = rounds, candidates = dev.wallpad.bridge.analysis.GuidedLearning.candidates(rounds),
                    message = if (learning.overflow) "500패킷 한도 초과: 이번 회차는 폐기했습니다." else "${rounds.size}/3회 이상 필요. 다음 회차 전 장치를 반대 상태로 돌려놓으세요. 수신이 없으면 회차를 저장하지 않습니다.",
                ))
            }
        }
    }

    fun clearPackets() = mutableState.update { it.copy(packets = emptyList(), groups = emptyList()) }

    override fun onCleared() {
        stopScan(updateState = false)
        if (hasBluetoothPermission()) {
            @SuppressLint("MissingPermission")
            bluetoothGatt?.close()
        }
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7bd10000-5f7b-4f4d-9c25-94c1e8f38a10")
        val DEVICE_INFO_UUID: UUID = UUID.fromString("7bd10001-5f7b-4f4d-9c25-94c1e8f38a10")
        val CONFIGURATION_UUID: UUID = UUID.fromString("7bd10002-5f7b-4f4d-9c25-94c1e8f38a10")
        val PACKET_STREAM_UUID: UUID = UUID.fromString("7bd10003-5f7b-4f4d-9c25-94c1e8f38a10")
        val RESPONSE_UUID: UUID = UUID.fromString("7bd10005-5f7b-4f4d-9c25-94c1e8f38a10")
        val DIAGNOSTICS_UUID: UUID = UUID.fromString("7bd10006-5f7b-4f4d-9c25-94c1e8f38a10")
        val OTA_CONTROL_UUID: UUID = UUID.fromString("7bd10007-5f7b-4f4d-9c25-94c1e8f38a10")
        val OTA_DATA_UUID: UUID = UUID.fromString("7bd10008-5f7b-4f4d-9c25-94c1e8f38a10")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_DURATION_MS = 8_000L
        const val PACKET_PREFIX_SIZE = 10
        const val MAX_PACKETS = 500
        const val OTA_BEGIN = 1
        const val OTA_FINISH = 2
        const val OTA_ABORT = 3
        const val OTA_QUERY = 4
        const val OTA_DATA_ACK = 5
        const val OTA_OK = 0
        const val RECONNECT_DELAY_MS = 4_000L
        const val CHUNK_INTERVAL_MS = 0L
        const val CHUNK_RETRY_DELAY_MS = 80L
        const val OTA_FINISH_TIMEOUT_MS = 20_000L
        const val MAX_CHUNK_RETRIES = 5
        const val OTA_ACK_TIMEOUT_MS = 1_500L
    }
}
