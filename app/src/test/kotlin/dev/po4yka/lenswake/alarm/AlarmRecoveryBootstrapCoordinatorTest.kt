package dev.po4yka.lenswake.alarm

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmRecoveryBootstrapCoordinatorTest {
    @Test
    fun lockedBootPersistsMinimalCheckpointWithoutStartingCredentialProtectedRecovery() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryServiceStarterSpy()
        val coordinator = coordinator(persistence, starter)

        val result = coordinator.handle(Intent.ACTION_LOCKED_BOOT_COMPLETED, userUnlocked = false)

        assertEquals(AlarmRecoveryBootstrapResult.DeferredUntilUnlock, result)
        assertNotNull(persistence.checkpoint())
        assertEquals(0, persistence.checkpoint()?.attempt)
        assertTrue(persistence.checkpoint()?.lastFailure?.contains("LOCKED_BOOT_COMPLETED") == true)
        assertTrue(starter.actions.isEmpty())
    }

    @Test
    fun unlockStartsRecoveryOnlyAfterDurableCheckpointExists() {
        val operations = mutableListOf<String>()
        val persistence = BootstrapCheckpointPersistence(operations)
        val starter = RecoveryServiceStarterSpy(operations = operations)
        val coordinator = coordinator(persistence, starter)
        coordinator.handle(Intent.ACTION_LOCKED_BOOT_COMPLETED, userUnlocked = false)

        val result = coordinator.handle(Intent.ACTION_USER_UNLOCKED, userUnlocked = true)

        assertEquals(AlarmRecoveryBootstrapResult.Started, result)
        assertEquals(listOf("persist", "persist", "start"), operations)
        assertEquals(listOf(Intent.ACTION_USER_UNLOCKED), starter.actions)
    }

    @Test
    fun lockedBootNeverStartsRecoveryEvenWhenUnlockStateIsMisreported() {
        val starter = RecoveryServiceStarterSpy()

        val result = coordinator(BootstrapCheckpointPersistence(), starter).handle(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            userUnlocked = true,
        )

        assertEquals(AlarmRecoveryBootstrapResult.DeferredUntilUnlock, result)
        assertTrue(starter.actions.isEmpty())
    }

    @Test
    fun foregroundServiceAdmissionFailureRetainsCheckpointAndSchedulesBoundedReceiverRetry() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryServiceStarterSpy(
            result = Result.failure(IllegalStateException("FGS start rejected")),
        )
        val retry = RecoveryFailureHandlerSpy(
            AlarmRecoveryRetryResult.Scheduled(attempt = 1, triggerAtEpochMillis = 31_000L),
        )
        val coordinator = AlarmRecoveryBootstrapCoordinator(
            persistence = persistence,
            serviceStarter = starter,
            failureHandler = retry,
            nowEpochMillis = { 1_000L },
        )

        val result = coordinator.handle(Intent.ACTION_BOOT_COMPLETED, userUnlocked = true)

        assertEquals(
            AlarmRecoveryBootstrapResult.Requeued(attempt = 1, triggerAtEpochMillis = 31_000L),
            result,
        )
        assertNotNull(persistence.checkpoint())
        assertFalse(persistence.clearCalled)
        assertTrue(retry.details.single().contains("FGS start rejected"))
    }

    @Test
    fun lockedRetryRemainsDeferredWithoutTouchingRecoveryService() {
        val persistence = BootstrapCheckpointPersistence(
            initial = AlarmRecoveryCheckpoint(
                attempt = 1,
                lastFailure = "FGS admission failed",
                nextAttemptAtEpochMillis = 31_000L,
                exhausted = false,
                updatedAtEpochMillis = 1_000L,
            ),
        )
        val starter = RecoveryServiceStarterSpy()

        val result = coordinator(persistence, starter).handle(
            ACTION_ALARM_RECOVERY_RETRY,
            userUnlocked = false,
        )

        assertEquals(AlarmRecoveryBootstrapResult.DeferredUntilUnlock, result)
        assertEquals(1, persistence.checkpoint()?.attempt)
        assertTrue(starter.actions.isEmpty())
    }

    private fun coordinator(
        persistence: BootstrapCheckpointPersistence,
        starter: RecoveryServiceStarterSpy,
    ) = AlarmRecoveryBootstrapCoordinator(
        persistence = persistence,
        serviceStarter = starter,
        failureHandler = RecoveryFailureHandlerSpy(
            AlarmRecoveryRetryResult.Scheduled(1, 31_000L),
        ),
        nowEpochMillis = { 1_000L },
    )
}

private class BootstrapCheckpointPersistence(
    private val operations: MutableList<String> = mutableListOf(),
    initial: AlarmRecoveryCheckpoint? = null,
) : AlarmRecoveryCheckpointPersistence {
    private var value = initial
    var clearCalled = false

    override fun checkpoint(): AlarmRecoveryCheckpoint? = value

    override fun persist(checkpoint: AlarmRecoveryCheckpoint): Boolean {
        operations += "persist"
        value = checkpoint
        return true
    }

    override fun clear(): Boolean {
        clearCalled = true
        value = null
        return true
    }
}

private class RecoveryServiceStarterSpy(
    private val result: Result<Unit> = Result.success(Unit),
    private val operations: MutableList<String> = mutableListOf(),
) : AlarmRecoveryServiceStarter {
    val actions = mutableListOf<String>()

    override fun start(action: String): Result<Unit> {
        operations += "start"
        actions += action
        return result
    }
}

private class RecoveryFailureHandlerSpy(
    private val result: AlarmRecoveryRetryResult,
) : AlarmRecoveryFailureHandler {
    val details = mutableListOf<String>()

    override fun retry(detail: String): AlarmRecoveryRetryResult {
        details += detail
        return result
    }
}
