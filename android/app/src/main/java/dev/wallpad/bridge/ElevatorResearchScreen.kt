package dev.wallpad.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wallpad.bridge.analysis.CapturedPacket
import dev.wallpad.bridge.analysis.ElevatorCandidates
import dev.wallpad.bridge.analysis.ElevatorOption

@Composable
fun ElevatorResearchScreen(state: DashboardState) {
    var option by remember { mutableStateOf(ElevatorOption.LONG) }
    var replay by remember { mutableStateOf(false) }
    val matches = remember(option, state.packets) {
        val scanner = ElevatorCandidates(option)
        state.packets.filter { it.direction == CapturedPacket.Direction.RX && !it.syntheticTestData }
            .flatMap { scanner.feed(it.bytes) }.takeLast(30)
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("엘리베이터 후보 검증", style = MaterialTheme.typography.headlineSmall)
            Text("HARDWARE_TEST_PENDING · 호출·도착·층수 미확인")
        }
        items(ElevatorOption.entries) { item ->
            Button(onClick = { option = item; replay = false }) {
                Text((if (item == option) "선택됨 · " else "") + item.title)
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("문서의 패킷 후보")
                    Text(option.sample)
                    Button(onClick = { replay = true }) { Text("오프라인 샘플 테스트") }
                    if (replay) {
                        val bytes = option.sample.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        Text("SYNTHETIC_TEST_DATA · " + ElevatorCandidates(option).feed(bytes).joinToString())
                    }
                    Text("이 테스트는 휴대폰 내부에서만 실행됩니다.")
                    Button(onClick = {}, enabled = false) { Text("실제 호출 송신 · 검증 대기") }
                }
            }
        }
        item { Text("최근 수신 구조 일치 ${matches.size}건 (최대 30건) · 장치 의미 미확인") }
        items(matches) { Text(it, style = MaterialTheme.typography.bodySmall) }
        item { Text("참고: polylog.tistory.com/231 · mscg.kr/65 · blog.oriang.net/24") }
    }
}
