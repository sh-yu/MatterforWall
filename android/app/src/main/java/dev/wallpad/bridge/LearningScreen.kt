package dev.wallpad.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wallpad.bridge.analysis.LearningPhase
import dev.wallpad.bridge.transport.ConnectionState

@Composable
fun LearningScreen(state: DashboardState, reset: (String, String) -> Unit, start: () -> Unit, save: (String, String) -> Unit) {
    var deviceName by remember { mutableStateOf("") }
    val learning = state.learning
    val capturing = learning.phase == LearningPhase.BASELINE || learning.phase == LearningPhase.ACTION
    val otaBusy = state.otaState in setOf(FirmwareUpdateState.STARTING, FirmwareUpdateState.UPLOADING,
        FirmwareUpdateState.VERIFYING, FirmwareUpdateState.REBOOTING)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("안내형 패킷 학습 · HARDWARE_TEST_PENDING")
            Text("1. 동작 선택 → 2. 평상시 5초 → 3. 조작 10초 → 4. 3회 이상 비교")
            Text("현재는 조명 학습만 지원합니다. 가스·승강기는 대상이 아닙니다.")
            Text("설치별 승인 조건: 지원 프로필 → 관리자 인증 → 송신 활성화 → 정상 버스 → 켜기·끄기 각각 3회 송수신 시험. 에코나 수신 횟수만으로 승인하지 않습니다.")
            Text("주의: 다른 장치를 조작하지 말고 조명 상태를 직접 확인하세요. 전기적 연결과 사용 권한은 사용자가 확인해야 합니다. 동의 문구는 안전 검증을 대체하지 않습니다.")
            Text("현재 배포본: 검증된 조명 프로필 0개 / 관리자 제어 인증 미구현. 시험 송신 및 자동 승인은 아직 사용할 수 없습니다.")
            OutlinedTextField(value = deviceName, onValueChange = { deviceName = it.take(48) }, label = { Text("조명 이름 · 예: 거실 1번") })
            Button(onClick = { reset("조명", "켜기") }, enabled = !capturing) { Text("조명 켜기 새 학습") }
            Button(onClick = { reset("조명", "끄기") }, enabled = !capturing) { Text("조명 끄기 새 학습") }
            Text("선택: ${learning.device} / ${learning.action} · 완료 ${learning.rounds.size}회")
            Text(learning.message)
            if (capturing) Text("남은 시간 ${learning.secondsLeft}초 · 평상시 ${learning.baseline.size} / 조작 ${learning.actionPackets.size}패킷")
            Button(onClick = start, enabled = !capturing && !otaBusy && state.connection == ConnectionState.READY && learning.rounds.size < 5) {
                Text("${learning.rounds.size + 1}회차 학습 시작")
            }
            Button(onClick = { reset(learning.device, learning.action) }) { Text("취소 / 초기화") }
            Text("각 구간 최대 500패킷. 후보를 저장하면 캡처 근거도 앱 내부 저장소에 보관됩니다.")
        }
        item {
            Text("반복 후보 ${learning.candidates.size}개")
            Text("평상시에도 보인 패킷은 제외합니다. 후보 없음은 실패 확정이 아닙니다: 시퀀스가 매번 바뀌는 프로토콜은 정확 일치에서 빠질 수 있습니다.")
        }
        items(learning.candidates, key = { it.hex }) { candidate ->
            Card {
                Column {
                    Text("${candidate.roundsSeen}회 반복 · 체크섬 후보: ${candidate.checksumCandidates.ifEmpty { setOf("알 수 없음") }}")
                    Text(candidate.hex)
                    Text("명령/응답 미확정 · HARDWARE_TEST_PENDING")
                    Button(onClick = { save(deviceName, candidate.hex) }, enabled = deviceName.isNotBlank() && !capturing) { Text("이 조명의 ${learning.action} 후보 저장") }
                    Button(onClick = {}, enabled = false) { Text("송신 잠김 · 검증 프로필 및 관리자 인증 필요") }
                }
            }
        }
        item { Text("수신 지점만으로 월패드와 장치의 송신 주체 또는 ACK를 확정할 수 없습니다. 체크섬 일치는 제어 검증이 아닙니다.") }
        item { Text("저장된 조명 학습 초안 ${state.learnedLights.size}개 · 검증 전") }
        items(state.learnedLights) { draft ->
            Text("${draft.name} · ${draft.action} · ${draft.roundsSeen}회 · ${draft.bridgeAddress}\n${draft.verification}")
        }
        item { Text("전체 수신 패킷 그룹") }
        items(state.groups) { group ->
            Text("${group.count}회 · 주기 ${group.estimatedPeriodMicros ?: "-"} μs\n" +
                group.exemplar.joinToString(" ") { "%02X".format(it) })
        }
    }
}
