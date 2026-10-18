package dev.wallpad.bridge.analysis

enum class LightAction { ON, OFF }

data class InstallationIdentity(
    val bridge: String,
    val profileId: String,
    val profileVersion: String,
    val address: String,
    val channel: String,
)

data class LightTestResult(
    val identity: InstallationIdentity,
    val action: LightAction,
    val sequence: Long,
    val commandSentAtMicros: Long,
    val responseAtMicros: Long,
    val responseMatches: Boolean,
    val responseIsEcho: Boolean,
    val physicalStateConfirmed: Boolean,
    val busError: Boolean,
    val synthetic: Boolean,
)

data class ApprovalRequirements(
    val identity: InstallationIdentity,
    val protocolReviewed: Boolean,
    val administratorAuthenticated: Boolean,
    val explicitTransmitEnabled: Boolean,
    val busHealthy: Boolean,
    val otaActive: Boolean,
    val baselineAndActionsLearned: Boolean,
    val responseWindowMicros: Long,
)

data class InstallationApproval(val mayTest: Boolean, val mayControl: Boolean, val reasons: List<String>)

/** Pure policy: UI acknowledgement and repeated RX cannot generate a test result.
 * Callers must supply correlated evidence from a reviewed protocol adapter.
 * Firmware must independently enforce the same authorization at transmission.
 */
object InstallationApprovalPolicy {
    const val REQUIRED_PER_ACTION = 3
    fun evaluate(requirements: ApprovalRequirements, results: List<LightTestResult>): InstallationApproval {
        val blockers = buildList {
            if (!requirements.protocolReviewed) add("검증된 프로토콜 프로필 필요")
            if (!requirements.administratorAuthenticated) add("ESP 관리자 인증 필요")
            if (!requirements.explicitTransmitEnabled) add("명시적 송신 활성화 필요")
            if (!requirements.busHealthy) add("버스 정상 상태 확인 필요")
            if (requirements.otaActive) add("OTA 중에는 송신 불가")
            if (!requirements.baselineAndActionsLearned) add("켜기·끄기 반복 학습 필요")
            val identity = requirements.identity
            if (listOf(identity.bridge, identity.profileId, identity.profileVersion, identity.address, identity.channel).any { it.isBlank() }) add("설치 식별 정보 필요")
            if (requirements.responseWindowMicros !in 1..5_000_000) add("프로필 응답 제한 시간 필요")
        }
        if (blockers.isNotEmpty()) return InstallationApproval(false, false, blockers)
        val on = mutableSetOf<Long>()
        val off = mutableSetOf<Long>()
        val sequences = mutableSetOf<Long>()
        for (result in results) {
            if (result.identity != requirements.identity) continue
            val elapsed = if (result.responseAtMicros >= result.commandSentAtMicros && result.commandSentAtMicros >= 0)
                result.responseAtMicros - result.commandSentAtMicros else -1
            val passed = result.sequence >= 0 && sequences.add(result.sequence) &&
                elapsed in 1..requirements.responseWindowMicros && result.responseMatches &&
                !result.responseIsEcho && result.physicalStateConfirmed && !result.busError && !result.synthetic
            if (!passed) {
                // A failed attempt invalidates the whole qualification run.
                on.clear(); off.clear()
            } else when (result.action) {
                LightAction.ON -> on.add(result.sequence)
                LightAction.OFF -> off.add(result.sequence)
            }
        }
        val approved = on.size >= REQUIRED_PER_ACTION && off.size >= REQUIRED_PER_ACTION
        return InstallationApproval(true, approved, if (approved) emptyList() else
            listOf("실제 송수신 시험: 켜기 ${on.size}/3 · 끄기 ${off.size}/3 연속 통과 필요"))
    }
}
