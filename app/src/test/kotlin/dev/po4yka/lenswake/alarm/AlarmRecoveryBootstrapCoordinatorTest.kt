package dev.po4yka.lenswake.alarm

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmRecoveryBootstrapCoordinatorTest {
    @Test
    fun lockedBootPersistsMinimalCheckpointWithoutSchedulingCredentialProtectedRecovery() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryJobSchedulerSpy()
        val coordinator = coordinator(persistence, starter)

        val result = coordinator.handle(Intent.ACTION_LOCKED_BOOT_COMPLETED, userUnlocked = false)

        assertEquals(AlarmRecoveryBootstrapResult.DeferredUntilUnlock, result)
        assertNotNull(persistence.checkpoint())
        assertEquals(0, persistence.checkpoint()?.attempt)
        assertTrue(persistence.checkpoint()?.lastFailure?.contains("LOCKED_BOOT_COMPLETED") == true)
        assertTrue(persistence.checkpoint()?.reconcileInterruptedSessions == true)
        assertTrue(starter.actions.isEmpty())
    }

    @Test
    fun unlockSchedulesRecoveryOnlyAfterDurableCheckpointExists() {
        val operations = mutableListOf<String>()
        val persistence = BootstrapCheckpointPersistence(operations)
        val starter = RecoveryJobSchedulerSpy(operations = operations)
        val coordinator = coordinator(persistence, starter)
        coordinator.handle(Intent.ACTION_LOCKED_BOOT_COMPLETED, userUnlocked = false)

        val result = coordinator.handle(Intent.ACTION_USER_UNLOCKED, userUnlocked = true)

        assertEquals(AlarmRecoveryBootstrapResult.Scheduled, result)
        assertEquals(listOf("persist", "persist", "schedule"), operations)
        assertEquals(listOf(Intent.ACTION_USER_UNLOCKED), starter.actions)
        assertTrue(persistence.checkpoint()?.reconcileInterruptedSessions == true)
    }

    @Test
    fun lockedBootNeverSchedulesRecoveryEvenWhenUnlockStateIsMisreported() {
        val starter = RecoveryJobSchedulerSpy()

        val result = coordinator(BootstrapCheckpointPersistence(), starter).handle(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            userUnlocked = true,
        )

        assertEquals(AlarmRecoveryBootstrapResult.DeferredUntilUnlock, result)
        assertTrue(starter.actions.isEmpty())
    }

    @Test
    fun jobSchedulingFailureRetainsCheckpointAndSchedulesBoundedReceiverRetry() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryJobSchedulerSpy(
            result = Result.failure(IllegalStateException("job rejected")),
        )
        val retry = RecoveryFailureHandlerSpy(
            AlarmRecoveryRetryResult.Scheduled(attempt = 1, triggerAtEpochMillis = 31_000L),
        )
        val coordinator = AlarmRecoveryBootstrapCoordinator(
            persistence = persistence,
            jobScheduler = starter,
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
        assertTrue(persistence.checkpoint()?.reconcileInterruptedSessions == true)
        assertTrue(retry.details.single().contains("job rejected"))
        assertEquals(listOf(false), retry.capabilityUnavailable)
    }

    @Test
    fun recoverySchedulingIsNotGatedByExactAlarmCapability() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryJobSchedulerSpy()
        val coordinator = AlarmRecoveryBootstrapCoordinator(
            persistence = persistence,
            jobScheduler = starter,
            failureHandler = RecoveryFailureHandlerSpy(
                AlarmRecoveryRetryResult.Scheduled(1, 31_000L),
            ),
            nowEpochMillis = { 1_000L },
        )

        val results = listOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
        ).map { action -> coordinator.handle(action, userUnlocked = true) }

        assertEquals(
            List(2) { AlarmRecoveryBootstrapResult.Scheduled },
            results,
        )
        assertEquals(
            listOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED),
            starter.actions,
        )
    }

    @Test
    fun nonBootRecoveryDoesNotReconcileInterruptedSessions() {
        val persistence = BootstrapCheckpointPersistence()
        val starter = RecoveryJobSchedulerSpy()

        val result = coordinator(persistence, starter).handle(
            Intent.ACTION_TIME_CHANGED,
            userUnlocked = true,
        )

        assertEquals(AlarmRecoveryBootstrapResult.Scheduled, result)
        assertFalse(persistence.checkpoint()?.reconcileInterruptedSessions == true)
    }

    @Test
    fun lockedRetryRemainsDeferredWithoutSchedulingRecoveryJob() {
        val persistence = BootstrapCheckpointPersistence(
            initial = AlarmRecoveryCheckpoint(
                attempt = 1,
                lastFailure = "Recovery job scheduling failed",
                nextAttemptAtEpochMillis = 31_000L,
                exhausted = false,
                updatedAtEpochMillis = 1_000L,
            ),
        )
        val starter = RecoveryJobSchedulerSpy()

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
        starter: RecoveryJobSchedulerSpy,
    ) = AlarmRecoveryBootstrapCoordinator(
        persistence = persistence,
        jobScheduler = starter,
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

private class RecoveryJobSchedulerSpy(
    private val result: Result<Unit> = Result.success(Unit),
    private val operations: MutableList<String> = mutableListOf(),
) : AlarmRecoveryJobScheduler {
    val actions = mutableListOf<String>()

    override fun schedule(action: String): Result<Unit> {
        operations += "schedule"
        actions += action
        return result
    }
}

private class RecoveryFailureHandlerSpy(
    private val result: AlarmRecoveryRetryResult,
) : AlarmRecoveryFailureHandler {
    val details = mutableListOf<String>()
    val capabilityUnavailable = mutableListOf<Boolean>()

    override fun retry(
        detail: String,
        capabilityUnavailable: Boolean,
    ): AlarmRecoveryRetryResult {
        details += detail
        this.capabilityUnavailable += capabilityUnavailable
        return result
    }
}
