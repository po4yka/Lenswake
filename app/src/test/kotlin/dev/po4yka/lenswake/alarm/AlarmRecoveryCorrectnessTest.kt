package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmRecoveryCorrectnessTest {
    @Test
    fun bootRecoveryReconcilesInterruptedSessionsButClockRecoveryDoesNot() = runBlocking {
        val scheduler = RecoveryRecordingScheduler()
        var reconciliationCalls = 0
        val coordinator = SchedulerAlarmRecoveryCoordinator(
            scheduler = scheduler,
            interruptedSessionRecovery = InterruptedScheduledSessionRecovery {
                reconciliationCalls += 1
                Result.success(Unit)
            },
        )

        assertTrue(coordinator.restoreFutureSchedules(true).isSuccess)
        assertTrue(coordinator.restoreFutureSchedules(false).isSuccess)

        assertEquals(1, reconciliationCalls)
        assertEquals(2, scheduler.restoreCalls)
    }

    @Test
    fun transientAccessibilityDisconnectDoesNotBlockRestoredAlarms() = runBlocking {
        val scheduler = RecoveryRecordingScheduler()
        val readiness = PreflightAlarmRecoveryReadiness {
            readyReport().replace(
                PreflightCheckType.ACCESSIBILITY_CONNECTED,
                PreflightStatus.FAILED,
                "Accessibility is not connected after reboot",
            )
        }
        val coordinator = SchedulerAlarmRecoveryCoordinator(
            scheduler = scheduler,
            readiness = readiness,
        )

        val result = coordinator.restoreFutureSchedules(false)

        assertTrue(result.isSuccess)
        assertEquals(1, scheduler.restoreCalls)
    }

    @Test
    fun disabledAccessibilityRetainsRecoveryFailureAfterAlarmsAreRestored() = runBlocking {
        val scheduler = RecoveryRecordingScheduler()
        val readiness = PreflightAlarmRecoveryReadiness {
            readyReport().replace(
                PreflightCheckType.ACCESSIBILITY_ENABLED,
                PreflightStatus.FAILED,
                "Accessibility is disabled",
            )
        }
        val coordinator = SchedulerAlarmRecoveryCoordinator(
            scheduler = scheduler,
            readiness = readiness,
        )

        val result = coordinator.restoreFutureSchedules(false)

        assertTrue(result.exceptionOrNull() is AlarmRecoveryReadinessException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Accessibility") == true)
        assertEquals(1, scheduler.restoreCalls)
    }

    @Test
    fun missingVerifiedProfileOrRehearsalEvidenceFailsClosed() = runBlocking {
        val readiness = PreflightAlarmRecoveryReadiness {
            readyReport().copy(
                checks = readyReport().checks.filterNot {
                    it.type == PreflightCheckType.REHEARSAL_CURRENT
                },
            )
        }

        val result = readiness.check()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("REHEARSAL_CURRENT") == true)
    }

    @Test
    fun unknownBatteryStateBlocksAlarmTimeAdmission() = runBlocking {
        val readiness = PreflightAlarmRecoveryReadiness {
            readyReport().replace(
                PreflightCheckType.BATTERY,
                PreflightStatus.UNKNOWN,
                "Battery capacity is unavailable",
            )
        }

        val result = readiness.check()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Battery capacity") == true)
    }

    @Test
    fun knownResourceWarningDoesNotBlockAlarmTimeAdmission() = runBlocking {
        val readiness = PreflightAlarmRecoveryReadiness {
            readyReport().copy(
                checks = readyReport().checks.map { check ->
                    if (check.type == PreflightCheckType.CHARGING) {
                        check.copy(
                            severity = PreflightSeverity.WARNING,
                            status = PreflightStatus.FAILED,
                            message = "Device is not charging",
                        )
                    } else {
                        check
                    }
                },
            )
        }

        assertTrue(readiness.check().isSuccess)
    }

    private fun readyReport() = PreflightReport(
        checks = REQUIRED_TYPES.map { type ->
            PreflightCheck(
                type = type,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.PASSED,
                message = "$type passed",
            )
        },
    )

    private fun PreflightReport.replace(
        type: PreflightCheckType,
        status: PreflightStatus,
        message: String,
    ) = copy(
        checks = checks.map { check ->
            if (check.type == type) check.copy(status = status, message = message) else check
        },
    )

    private companion object {
        val REQUIRED_TYPES = listOf(
            PreflightCheckType.EXACT_ALARMS,
            PreflightCheckType.PIXEL_CAMERA_INSTALLED,
            PreflightCheckType.SECURE_CAMERA_RESOLVES,
            PreflightCheckType.DEVICE_WAKE,
            PreflightCheckType.ACCESSIBILITY_ENABLED,
            PreflightCheckType.ACCESSIBILITY_CONNECTED,
            PreflightCheckType.PROFILE_AVAILABLE,
            PreflightCheckType.PROFILE_COMPATIBILITY,
            PreflightCheckType.REHEARSAL_CURRENT,
            PreflightCheckType.BATTERY,
            PreflightCheckType.CHARGING,
            PreflightCheckType.STORAGE,
        )
    }
}

private class RecoveryRecordingScheduler : RecordingScheduler {
    var restoreCalls = 0

    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> = Result.success(Unit)

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> = Result.success(Unit)

    override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)

    override suspend fun restoreAll(): Result<Unit> {
        restoreCalls += 1
        return Result.success(Unit)
    }
}
