package dev.wallpad.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wallpad.bridge.transport.ConnectionState
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2457D6),
                    secondary = Color(0xFF4766A8),
                    surface = Color(0xFFF9F9FC),
                    background = Color(0xFFF4F6FA),
                ),
            ) { ManagerApp() }
        }
    }
}

private enum class Screen(val label: String, val icon: ImageVector) {
    DASHBOARD("홈", Icons.Default.Dashboard),
    MONITOR("모니터", Icons.Default.WifiTethering),
    ANALYZER("분석", Icons.Default.Analytics),
    ELEVATOR("승강기", Icons.Default.Tune),
    FIRMWARE("업데이트", Icons.Default.SystemUpdate),
    SETTINGS("설정", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::selectFirmware)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Wallpad Bridge", fontWeight = FontWeight.SemiBold)
                        Text("실시간 연결 · 검증 기반 제어", style = MaterialTheme.typography.labelSmall, color = Color(0xFF667085))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.DASHBOARD -> Dashboard(state, vm::scan, vm::connect, vm::disconnect)
                Screen.MONITOR -> Monitor(state, vm::togglePause, vm::clearPackets)
                Screen.ANALYZER -> LearningScreen(state, vm::resetLearning, vm::startLearningRound, vm::saveLearnedLight)
                Screen.ELEVATOR -> ElevatorResearchScreen(state)
                Screen.FIRMWARE -> FirmwareScreen(
                    state = state,
                    chooseFile = { firmwarePicker.launch(arrayOf("*/*")) },
                    startUpdate = vm::startFirmwareUpdate,
                    abortUpdate = vm::abortFirmwareUpdate,
                    setRepository = vm::setReleaseRepository,
                    checkRelease = vm::checkRelease,
                    downloadRelease = vm::downloadRelease,
                )
                Screen.SETTINGS -> SettingsScreen(state)
            }
        }
    }
}

@Composable
private fun Dashboard(
    state: DashboardState,
    scan: () -> Unit,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ConnectionCard(state, scan, disconnect) }

        if (state.discoveredDevices.isNotEmpty() && state.connection != ConnectionState.READY) {
            item { SectionTitle("검색된 기기") }
            items(state.discoveredDevices, key = { it.address }) { device ->
                DiscoveredDeviceCard(device) { connect(device.address) }
            }
        }

        item { SectionTitle("시스템 상태") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.WifiTethering,
                    label = "RS485",
                    value = if (state.connection == ConnectionState.READY) "수신 대기" else "연결 필요",
                    healthy = state.connection == ConnectionState.READY,
                )
                StatusTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Memory,
                    label = "패킷",
                    value = "${state.rxFrames}개",
                    healthy = state.rxFrames > 0,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusTile(Modifier.weight(1f), Icons.Default.Shield, "Matter", state.matter, false)
                StatusTile(Modifier.weight(1f), Icons.Default.Tune, "Thread", state.thread, false)
            }
        }
        item { DeviceDetails(state) }
    }
}

@Composable
private fun ConnectionCard(state: DashboardState, scan: () -> Unit, disconnect: () -> Unit) {
    val connected = state.connection == ConnectionState.READY
    val busy = state.connection in setOf(
        ConnectionState.SCANNING,
        ConnectionState.CONNECTING,
        ConnectionState.AUTHENTICATING,
    )
    val accent = when {
        connected -> Color(0xFF0B7A53)
        state.connection == ConnectionState.ERROR -> Color(0xFFB42318)
        busy -> Color(0xFF2457D6)
        else -> Color(0xFF667085)
    }
    val title = when (state.connection) {
        ConnectionState.READY -> "ESP 브리지 연결됨"
        ConnectionState.SCANNING -> "ESP 브리지 검색 중"
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> "ESP 브리지 연결 중"
        ConnectionState.ERROR -> "연결 오류"
        ConnectionState.DISCONNECTED -> "ESP 브리지 연결 안 됨"
    }
    val statusLabel = when (state.connection) {
        ConnectionState.READY -> "온라인"
        ConnectionState.SCANNING -> "검색 중"
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> "연결 중"
        ConnectionState.ERROR -> "오류"
        ConnectionState.DISCONNECTED -> "오프라인"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                    )
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(accent, CircleShape))
                        Text(
                            " $statusLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    state.deviceName?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF667085))
                    }
                }
            }
            Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF475467))
            if (connected) {
                FilledTonalButton(onClick = disconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("연결 해제")
                }
            } else {
                Button(
                    onClick = scan,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(if (busy) " 확인 중…" else " ESP 브리지 찾기")
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(device: DiscoveredBridge, connect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text("신호 ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = Color(0xFF667085))
            }
            Button(onClick = connect) { Text("연결") }
        }
    }
}

@Composable
private fun StatusTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    healthy: Boolean,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = if (healthy) Color(0xFF0B7A53) else Color(0xFF98A2B3))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF667085))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceDetails(state: DashboardState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("연결 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DetailRow("연결 방식", "Bluetooth LE")
            HorizontalDivider(color = Color(0xFFEAECF0))
            DetailRow("기기", state.deviceName ?: "연결된 기기 없음")
            DetailRow("펌웨어", state.firmwareVersion)
            DetailRow("동작 모드", state.operatingMode)
            DetailRow("여유 메모리", state.freeHeap?.let(::formatBytes) ?: "확인되지 않음")
            DetailRow("최소 여유 메모리", state.minimumFreeHeap?.let(::formatBytes) ?: "확인되지 않음")
            DetailRow(
                "최근 패킷",
                state.lastPacketAtMillis?.let { DateFormat.getTimeInstance().format(Date(it)) }
                    ?: "수신 기록 없음",
            )
            DetailRow("RS485 오류", "${state.rs485Errors}회")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF667085), modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Monitor(state: DashboardState, togglePause: () -> Unit, clear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("RS485 모니터", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (state.connection == ConnectionState.READY) "실시간 수신 패킷" else "ESP 브리지를 먼저 연결하세요.",
            color = Color(0xFF667085),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = togglePause, enabled = state.connection == ConnectionState.READY) {
                Text(if (state.paused) "계속" else "일시정지")
            }
            FilledTonalButton(onClick = clear, enabled = state.packets.isNotEmpty()) { Text("비우기") }
        }
        Spacer(Modifier.height(12.dp))
        if (state.packets.isEmpty()) {
            EmptyState("아직 수신된 패킷이 없습니다.")
        } else {
            LazyColumn {
                items(state.packets.asReversed()) { packet ->
                    Text("${packet.timestampMicros}  ${packet.direction}  ${packet.hex}")
                }
            }
        }
    }
}

@Composable
private fun Analyzer(state: DashboardState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("패킷 분석", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("실제 수신 데이터만 분석합니다.", color = Color(0xFF667085))
        Spacer(Modifier.height(16.dp))
        if (state.groups.isEmpty()) {
            EmptyState("분석할 패킷 그룹이 없습니다.")
        } else {
            LazyColumn {
                items(state.groups) { group ->
                    Text(
                        "${group.count}회 · 주기 ${group.estimatedPeriodMicros ?: "-"} μs\n" +
                            group.exemplar.joinToString(" ") { "%02X".format(it) },
                        Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FirmwareScreen(
    state: DashboardState,
    chooseFile: () -> Unit,
    startUpdate: () -> Unit,
    abortUpdate: () -> Unit,
    setRepository: (String) -> Unit,
    checkRelease: () -> Unit,
    downloadRelease: () -> Unit,
) {
    val busy = state.otaState in setOf(
        FirmwareUpdateState.STARTING,
        FirmwareUpdateState.UPLOADING,
        FirmwareUpdateState.VERIFYING,
        FirmwareUpdateState.REBOOTING,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("펌웨어 OTA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("서명된 ESP 펌웨어만 Bluetooth LE로 설치합니다.", color = Color(0xFF667085))
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.releaseRepository, onValueChange = setRepository,
                        label = { Text("GitHub 저장소 (owner/repository)") },
                        singleLine = true, enabled = !busy && !state.releaseBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(state.releaseMessage)
                    Button(onClick = checkRelease, enabled = !busy && !state.releaseBusy && state.releaseRepository.isNotBlank()) {
                        Text("최신 업데이트 확인")
                    }
                    if (state.availableRelease != null) {
                        Button(onClick = downloadRelease, enabled = !busy && !state.releaseBusy && state.connection == ConnectionState.READY) { Text("다운로드 및 업데이트") }
                    }
                    Text("검증한 파일은 캐시에 보관하고 바로 ESP 업데이트를 시작합니다.")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("현재 버전", state.firmwareVersion)
                    DetailRow("Matter", state.matter)
                    DetailRow("Thread", state.thread)
                    state.matterManualCode?.let { code ->
                        DetailRow("Matter 연결 코드", code)
                        Text("SmartThings에서 Matter 기기 추가 → 코드 직접 입력으로 연결하세요. 테스트 인증서 기반 펌웨어입니다.")
                    }
                    DetailRow("선택 파일", state.firmwareFileName ?: "선택되지 않음")
                    DetailRow("새 버전", state.firmwareCandidateVersion ?: "-")
                    DetailRow("파일 크기", if (state.firmwareCandidateSize > 0) formatBytes(state.firmwareCandidateSize.toLong()) else "-")
                    state.firmwareSha256?.let { hash -> DetailRow("SHA-256", hash.take(12) + "…") }
                    Button(onClick = chooseFile, enabled = !busy && !state.releaseBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("수동 파일 선택 · 복구용")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("업데이트 상태", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { state.otaProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${state.otaProgress}% · ${state.otaMessage}", color = Color(0xFF475467))
                    if (busy && state.otaState != FirmwareUpdateState.REBOOTING) {
                        FilledTonalButton(onClick = abortUpdate, modifier = Modifier.fillMaxWidth()) { Text("업데이트 취소") }
                    } else {
                        Button(
                            onClick = startUpdate,
                            enabled = !state.releaseBusy && state.connection == ConnectionState.READY &&
                                state.otaState in setOf(FirmwareUpdateState.FILE_READY, FirmwareUpdateState.ERROR, FirmwareUpdateState.COMPLETE),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("OTA 업데이트 시작") }
                    }
                }
            }
        }
        item {
            Text(
                "검증 순서: ESP 이미지 → 프로젝트 ID → 서명 블록 → 버전 → SHA-256 → 기기 내 암호 서명. " +
                    "업데이트 중 RS485 송신은 강제로 차단됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF667085),
            )
        }
    }
}

@Composable
private fun SettingsScreen(state: DashboardState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        AppUpdateCard(
            repository = state.releaseRepository,
            enabled = state.otaState !in setOf(FirmwareUpdateState.STARTING, FirmwareUpdateState.UPLOADING,
                FirmwareUpdateState.VERIFYING, FirmwareUpdateState.REBOOTING),
        )
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("제어 보안 정책", fontWeight = FontWeight.Bold)
                Text("검증된 조명 프로필만 관리자 승인 후 송신합니다.", color = Color(0xFF667085))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = null)
                    Text("  가스 열기 및 미검증 승강기 송신은 항상 차단됩니다.")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Text(message, Modifier.padding(24.dp), color = Color(0xFF667085))
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}
