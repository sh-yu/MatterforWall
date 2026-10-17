package dev.wallpad.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wallpad.bridge.ota.AppUpdateViewModel

@Composable
fun AppUpdateCard(repository: String, enabled: Boolean, vm: AppUpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("앱 업데이트", style = MaterialTheme.typography.titleMedium)
            Text("GitHub · $repository")
            Text(state.message)
            Button(onClick = { vm.check(repository) }, enabled = enabled && !state.busy) { Text("앱 최신 버전 확인") }
            if (state.release != null && !state.ready) {
                Button(onClick = vm::download, enabled = enabled && !state.busy) { Text("최신 APK 다운로드") }
            }
            if (state.ready) {
                Button(onClick = vm::install, enabled = enabled && !state.busy) { Text("앱 설치") }
            }
        }
    }
}
