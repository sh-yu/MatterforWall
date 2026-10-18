package dev.wallpad.bridge.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationApprovalTest {
    // SYNTHETIC_TEST_DATA: exercises policy only, never registers a real profile.
    private val identity = InstallationIdentity("bridge", "test-only", "1", "address", "channel")
    private val requirements = ApprovalRequirements(identity, true, true, true, true, false, true, 1000)
    private fun results() = (0L..5L).map { index ->
        LightTestResult(identity, if (index % 2 == 0L) LightAction.ON else LightAction.OFF,
            index, index * 2000, index * 2000 + 100, true, false, true, false, false)
    }
    @Test fun requiresThreeOnAndThreeOff() {
        assertFalse(InstallationApprovalPolicy.evaluate(requirements, results().take(5)).mayControl)
        assertTrue(InstallationApprovalPolicy.evaluate(requirements, results()).mayControl)
    }
    @Test fun countsNeverOverrideMissingSafetyGates() {
        for (blocked in listOf(requirements.copy(protocolReviewed = false),
            requirements.copy(administratorAuthenticated = false), requirements.copy(explicitTransmitEnabled = false),
            requirements.copy(busHealthy = false), requirements.copy(otaActive = true))) {
            val approval = InstallationApprovalPolicy.evaluate(blocked, results())
            assertFalse(approval.mayTest)
            assertFalse(approval.mayControl)
        }
    }
    @Test fun rejectsEchoSyntheticTimeoutAndUserOnlyConfirmation() {
        for (invalid in listOf(results().last().copy(responseIsEcho = true),
            results().last().copy(synthetic = true), results().last().copy(responseAtMicros = 999999),
            results().last().copy(responseMatches = false), results().last().copy(physicalStateConfirmed = false),
            results().last().copy(busError = true))) {
            assertFalse(InstallationApprovalPolicy.evaluate(requirements, results().dropLast(1) + invalid).mayControl)
        }
    }
    @Test fun changedProfileAndDuplicateResultsCannotQualify() {
        assertFalse(InstallationApprovalPolicy.evaluate(requirements.copy(identity = identity.copy(profileVersion = "2")), results()).mayControl)
        assertFalse(InstallationApprovalPolicy.evaluate(requirements, List(10) { results().first() }).mayControl)
    }
}
