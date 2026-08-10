package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmTransportEscalationTest {
    @Test
    fun corruptJournalEntryPersistsStableCameraSafetyIncident() {
        val persistence = FakeFailurePersistence()
        val escalator = AlarmTransportEscalator(
            persistence = persistence,
            notifier = FakeFailureNotifier(),
            nowEpochMillis = { 5_000L },
        )
        val corruptEntry = AlarmDeliveryJournal.CorruptEntry(
            key = "private-preference-key",
            reason = AlarmDeliveryJournal.CorruptionReason.UnrecognizedDeliveryWork,
        )

        escalator.escalateJournalCorruption(corruptEntry)
        escalator.escalateJournalCorruption(corruptEntry)

        val marker = persistence.single()
        assertEquals(AlarmTransportFailureCode.JOURNAL_ENTRY_CORRUPT, marker.code)
        assertEquals(
            AlarmTransportEscalator.journalCorruptionMarkerId(corruptEntry.key),
            marker.id,
        )
        assertTrue(marker.cameraAction)
        assertTrue(marker.message.contains("unknown START or STOP"))
        assertTrue(!marker.id.contains(corruptEntry.key))
    }

    @Test
    fun corruptJournalIncidentPersistenceFailureDurablyRequeuesRecoveryOnce() {
        val failurePersistence = FakeFailurePersistence(persistSucceeds = false)
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val recoveryBackend = FakeRecoveryRetryBackend()
        val escalator = AlarmTransportEscalator(
            persistence = failurePersistence,
            notifier = FakeFailureNotifier(),
            nowEpochMillis = { 5_000L },
        )
        val handler = AlarmJournalCorruptionDeliveryHandler(
            escalator = escalator,
            recoveryCoordinator = AlarmRecoveryRetryCoordinator(
                persistence = checkpoint,
                backend = recoveryBackend,
                escalator = escalator,
                nowEpochMillis = { 1_000L },
            ),
        )
        val corruptEntries = listOf(
            AlarmDeliveryJournal.CorruptEntry(
                key = "corrupt-one",
                reason = AlarmDeliveryJournal.CorruptionReason.NonStringValue,
            ),
            AlarmDeliveryJournal.CorruptEntry(
                key = "corrupt-two",
                reason = AlarmDeliveryJournal.CorruptionReason.UnrecognizedDeliveryWork,
            ),
        )

        val result = handler.handle(corruptEntries)

        assertTrue(result.escalations.all { !it.markerPersisted })
        assertEquals(AlarmRecoveryRetryResult.Scheduled(1, 31_000L), result.recovery)
        assertEquals(1, checkpoint.checkpoint()?.attempt)
        assertEquals(listOf(31_000L), recoveryBackend.scheduled)
    }

    @Test
    fun initialJournalFailureRequeuesStopWithoutJournalReplacement() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(canSchedule = true)
        val coordinator = deliveryCoordinator(
            persistence,
            FakeFailureNotifier(),
            backend,
        )
        val work = AlarmDeliveryWork.Schedule(trigger(kind = AlarmKind.STOP))

        val result = coordinator.scheduleUnjournaledRetry(
            work,
            "Initial durable journal write failed.",
        )

        assertTrue(result is AlarmDeliveryRetryResult.Scheduled)
        assertTrue(backend.replaced.isEmpty())
        val retry = backend.scheduled.single() as AlarmDeliveryWork.Schedule
        assertEquals(AlarmKind.STOP, retry.trigger.kind)
        assertEquals(1, retry.trigger.deliveryAttempt)
        assertTrue(persistence.markers().isEmpty())
    }

    @Test
    fun initialStopJournalFailureEscalatesWhenIndependentRetryCannotBeScheduled() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(
            canSchedule = true,
            schedulingResult = Result.failure(IllegalStateException("alarm manager rejected")),
        )
        val coordinator = deliveryCoordinator(
            persistence,
            FakeFailureNotifier(),
            backend,
        )
        val work = AlarmDeliveryWork.Schedule(trigger(kind = AlarmKind.STOP))

        val result = coordinator.scheduleUnjournaledRetry(
            work,
            "Initial durable journal write failed.",
        )

        assertTrue(result is AlarmDeliveryRetryResult.Escalated)
        assertEquals(
            AlarmTransportFailureCode.EXACT_ALARM_SCHEDULING_FAILED,
            persistence.single().code,
        )
        assertTrue(persistence.single().message.contains("Pixel Camera may still be recording"))
        assertTrue(backend.replaced.isEmpty())
        assertTrue(backend.restored.isEmpty())
    }

    @Test
    fun repeatedInitialJournalFailureStopsAtBoundedAttemptAndEscalatesStop() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(canSchedule = true)
        val coordinator = deliveryCoordinator(
            persistence,
            FakeFailureNotifier(),
            backend,
        )
        val work = AlarmDeliveryWork.Schedule(trigger(kind = AlarmKind.STOP, attempt = 2))

        val result = coordinator.scheduleUnjournaledRetry(
            work,
            "Initial durable journal write failed repeatedly.",
        )

        assertTrue(result is AlarmDeliveryRetryResult.Escalated)
        assertEquals(
            AlarmTransportFailureCode.RETRY_ATTEMPTS_EXHAUSTED,
            persistence.single().code,
        )
        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun exhaustedStopPersistsManualActionMarkerWhenNotificationPermissionIsUnavailable() {
        val persistence = FakeFailurePersistence()
        val notifier = FakeFailureNotifier(FailureNotificationResult.PERMISSION_UNAVAILABLE)
        val backend = FakeDeliveryRetryBackend(canSchedule = true)
        val coordinator = deliveryCoordinator(persistence, notifier, backend)
        val trigger = trigger(kind = AlarmKind.STOP, attempt = 2)

        val result = coordinator.scheduleRetry(entry(trigger), "Database is unavailable.")

        assertTrue(result is AlarmDeliveryRetryResult.Escalated)
        assertEquals(AlarmTransportFailureCode.RETRY_ATTEMPTS_EXHAUSTED, persistence.single().code)
        assertTrue(persistence.single().message.contains("Pixel Camera may still be recording"))
        assertEquals("Open Pixel Camera", persistence.single().actionLabel)
        assertEquals(FailureNotificationResult.PERMISSION_UNAVAILABLE, notifier.lastResult)
        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun exactAlarmDenialPersistsFailureWithoutIncrementingOrReplacingJournal() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(canSchedule = false)
        val coordinator = deliveryCoordinator(persistence, FakeFailureNotifier(), backend)

        val result = coordinator.scheduleRetry(entry(trigger()), "Retry requested.")

        assertTrue(result is AlarmDeliveryRetryResult.Escalated)
        assertEquals(AlarmTransportFailureCode.EXACT_ALARM_UNAVAILABLE, persistence.single().code)
        assertTrue(backend.replaced.isEmpty())
        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun journalUpdateFailurePersistsTerminalTransportMarker() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(canSchedule = true, replacementSucceeds = false)
        val coordinator = deliveryCoordinator(persistence, FakeFailureNotifier(), backend)

        coordinator.scheduleRetry(entry(trigger()), "Retry requested.")

        assertEquals(AlarmTransportFailureCode.JOURNAL_UPDATE_FAILED, persistence.single().code)
        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun exactSchedulingFailureRestoresJournalAndPersistsMarker() {
        val persistence = FakeFailurePersistence()
        val backend = FakeDeliveryRetryBackend(
            canSchedule = true,
            schedulingResult = Result.failure(IllegalStateException("alarm manager rejected")),
        )
        val coordinator = deliveryCoordinator(persistence, FakeFailureNotifier(), backend)
        val trigger = trigger()

        coordinator.scheduleRetry(entry(trigger), "Retry requested.")

        assertEquals(listOf(AlarmDeliveryWork.Schedule(trigger)), backend.restored)
        assertEquals(
            AlarmTransportFailureCode.EXACT_ALARM_SCHEDULING_FAILED,
            persistence.single().code,
        )
    }

    @Test
    fun eventualResolutionClearsMarkerAndNotification() {
        val persistence = FakeFailurePersistence()
        val notifier = FakeFailureNotifier()
        val backend = FakeDeliveryRetryBackend(canSchedule = false)
        val coordinator = deliveryCoordinator(persistence, notifier, backend)
        val trigger = trigger(kind = AlarmKind.STOP)
        coordinator.scheduleRetry(entry(trigger), "Retry requested.")

        assertTrue(coordinator.resolve(AlarmDeliveryWork.Schedule(trigger)))

        assertTrue(persistence.markers().isEmpty())
        assertEquals(listOf(AlarmDeliveryWork.Schedule(trigger)), backend.cancelled)
        assertEquals(listOf(AlarmTransportEscalator.deliveryMarkerId(trigger)), notifier.dismissed)
    }

    @Test
    fun terminalStopRejectionKeepsManualStopWarningUntilPositiveResolution() {
        val persistence = FakeFailurePersistence()
        val notifier = FakeFailureNotifier()
        val backend = FakeDeliveryRetryBackend(canSchedule = true)
        val coordinator = deliveryCoordinator(persistence, notifier, backend)
        val trigger = trigger(kind = AlarmKind.STOP)

        val result = coordinator.escalateTerminalStop(
            AlarmDeliveryWork.Schedule(trigger),
            "The schedule was deleted before STOP delivery.",
        )

        assertTrue(result.markerPersisted)
        assertEquals(AlarmTransportFailureCode.STOP_TERMINAL_REJECTED, persistence.single().code)
        assertTrue(persistence.single().message.contains("stop it manually"))
        assertTrue(notifier.dismissed.isEmpty())
        assertEquals(listOf(AlarmDeliveryWork.Schedule(trigger)), backend.cancelled)
    }

    @Test
    fun rehearsalStopUsesSameDurableBoundedRetryTransport() {
        val backend = FakeDeliveryRetryBackend(canSchedule = true)
        val coordinator = deliveryCoordinator(
            FakeFailurePersistence(),
            FakeFailureNotifier(),
            backend,
        )
        val work = AlarmDeliveryWork.RehearsalStop(
            RehearsalStopTrigger(
                sessionId = SessionId("rehearsal-1"),
                expectedAt = Instant.parse("2026-08-09T11:00:00Z"),
            ),
        )

        val result = coordinator.scheduleRetry(
            AlarmDeliveryJournal.Entry("entry-key", work),
            "Coordinator temporarily unavailable.",
        )

        assertTrue(result is AlarmDeliveryRetryResult.Scheduled)
        val replacement = backend.replaced.single() as AlarmDeliveryWork.RehearsalStop
        assertEquals(1, replacement.trigger.deliveryAttempt)
        assertEquals(listOf(replacement), backend.scheduled)
    }

    private fun deliveryCoordinator(
        persistence: FakeFailurePersistence,
        notifier: FakeFailureNotifier,
        backend: FakeDeliveryRetryBackend,
    ) = AlarmDeliveryRetryCoordinator(
        backend = backend,
        escalator = AlarmTransportEscalator(persistence, notifier) { 5_000L },
        nowEpochMillis = { 1_000L },
    )
}

class AlarmRecoveryRetryCoordinatorTest {
    @Test
    fun recoveryFailurePersistsCheckpointBeforeBoundedBackoffRequeue() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val backend = FakeRecoveryRetryBackend()
        val coordinator = recoveryCoordinator(checkpoint, backend, FakeFailurePersistence())

        val result = coordinator.retry("Room was unavailable.")

        assertEquals(AlarmRecoveryRetryResult.Scheduled(1, 31_000L), result)
        assertEquals(1, checkpoint.checkpoint()?.attempt)
        assertEquals(31_000L, checkpoint.checkpoint()?.nextAttemptAtEpochMillis)
        assertEquals(listOf(31_000L), backend.scheduled)
    }

    @Test
    fun rebootReconciliationRequirementSurvivesRetryCheckpointReplacement() {
        val checkpoint = FakeRecoveryCheckpointPersistence(
            AlarmRecoveryCheckpoint(
                attempt = 0,
                lastFailure = "Boot recovery requested.",
                nextAttemptAtEpochMillis = null,
                exhausted = false,
                updatedAtEpochMillis = 500L,
                reconcileInterruptedSessions = true,
            ),
        )
        val coordinator = recoveryCoordinator(
            checkpoint,
            FakeRecoveryRetryBackend(),
            FakeFailurePersistence(),
        )

        coordinator.retry("Room was unavailable.")

        assertTrue(checkpoint.checkpoint()?.reconcileInterruptedSessions == true)
    }

    @Test
    fun repeatedRecoveryFailuresStopAfterBoundedAttemptsAndPersistMarker() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val backend = FakeRecoveryRetryBackend()
        val failures = FakeFailurePersistence()
        val coordinator = recoveryCoordinator(checkpoint, backend, failures)

        coordinator.retry("failure one")
        coordinator.retry("failure two")
        val exhausted = coordinator.retry("failure three")

        assertTrue(exhausted is AlarmRecoveryRetryResult.Escalated)
        assertEquals(listOf(31_000L, 61_000L), backend.scheduled)
        assertTrue(checkpoint.checkpoint()?.exhausted == true)
        assertNull(checkpoint.checkpoint()?.nextAttemptAtEpochMillis)
        assertEquals(AlarmTransportFailureCode.RECOVERY_ATTEMPTS_EXHAUSTED, failures.single().code)
    }

    @Test
    fun recoveryRequeueFailurePersistsExhaustedCheckpointAndMarker() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val backend = FakeRecoveryRetryBackend(Result.failure(IllegalStateException("rejected")))
        val failures = FakeFailurePersistence()
        val coordinator = recoveryCoordinator(checkpoint, backend, failures)

        val result = coordinator.retry("Recovery failed.")

        assertTrue(result is AlarmRecoveryRetryResult.Escalated)
        assertTrue(checkpoint.checkpoint()?.exhausted == true)
        assertEquals(AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED, failures.single().code)
    }

    @Test
    fun capabilityFailureEscalatesWithoutPointlessRecoveryLoop() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val backend = FakeRecoveryRetryBackend()
        val failures = FakeFailurePersistence()
        val coordinator = recoveryCoordinator(checkpoint, backend, failures)

        coordinator.retry("Exact alarms unavailable.", capabilityUnavailable = true)

        assertTrue(backend.scheduled.isEmpty())
        assertTrue(checkpoint.checkpoint()?.exhausted == true)
        assertEquals(
            AlarmTransportFailureCode.RECOVERY_CAPABILITY_UNAVAILABLE,
            failures.single().code,
        )
    }

    @Test
    fun successfulRecoveryClearsCheckpointMarkerAndNotification() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val failures = FakeFailurePersistence()
        val notifier = FakeFailureNotifier()
        val backend = FakeRecoveryRetryBackend()
        val coordinator = AlarmRecoveryRetryCoordinator(
            persistence = checkpoint,
            backend = backend,
            escalator = AlarmTransportEscalator(failures, notifier) { 5_000L },
            nowEpochMillis = { 1_000L },
        )
        coordinator.retry("Exact alarms unavailable.", capabilityUnavailable = true)

        assertTrue(coordinator.resolve())

        assertNull(checkpoint.checkpoint())
        assertTrue(failures.markers().isEmpty())
        assertEquals(1, backend.cancelCount)
        assertEquals(listOf(AlarmTransportEscalator.RECOVERY_MARKER_ID), notifier.dismissed)
    }

    @Test
    fun successfulRetryJobDoesNotCancelItsOwnSchedulerIdentity() {
        val checkpoint = FakeRecoveryCheckpointPersistence()
        val failures = FakeFailurePersistence()
        val backend = FakeRecoveryRetryBackend()
        val coordinator = recoveryCoordinator(checkpoint, backend, failures)
        coordinator.retry("Room was unavailable.")

        assertTrue(coordinator.resolve(cancelScheduledRetry = false))

        assertNull(checkpoint.checkpoint())
        assertEquals(0, backend.cancelCount)
    }

    private fun recoveryCoordinator(
        checkpoint: FakeRecoveryCheckpointPersistence,
        backend: FakeRecoveryRetryBackend,
        failures: FakeFailurePersistence,
    ) = AlarmRecoveryRetryCoordinator(
        persistence = checkpoint,
        backend = backend,
        escalator = AlarmTransportEscalator(failures, FakeFailureNotifier()) { 5_000L },
        nowEpochMillis = { 1_000L },
    )
}

private class FakeFailurePersistence(
    private val persistSucceeds: Boolean = true,
) : AlarmTransportFailurePersistence {
    private val values = linkedMapOf<String, AlarmTransportFailureMarker>()

    override fun persist(marker: AlarmTransportFailureMarker): Boolean {
        if (!persistSucceeds) return false
        values[marker.id] = marker
        return true
    }

    override fun remove(id: String): Boolean {
        values.remove(id)
        return true
    }

    override fun markers(): List<AlarmTransportFailureMarker> = values.values.toList()

    fun single(): AlarmTransportFailureMarker = values.values.single()
}

private class FakeFailureNotifier(
    private val publishResult: FailureNotificationResult = FailureNotificationResult.PUBLISHED,
) : AlarmTransportFailureNotifier {
    val dismissed = mutableListOf<String>()
    var lastResult: FailureNotificationResult? = null

    override fun publish(marker: AlarmTransportFailureMarker): FailureNotificationResult =
        publishResult.also { lastResult = it }

    override fun dismiss(id: String) {
        dismissed += id
    }
}

private class FakeDeliveryRetryBackend(
    private val canSchedule: Boolean,
    private val replacementSucceeds: Boolean = true,
    private val schedulingResult: Result<Unit> = Result.success(Unit),
) : AlarmDeliveryRetryBackend {
    val replaced = mutableListOf<AlarmDeliveryWork>()
    val scheduled = mutableListOf<AlarmDeliveryWork>()
    val restored = mutableListOf<AlarmDeliveryWork>()
    val cancelled = mutableListOf<AlarmDeliveryWork>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun replaceJournalEntry(
        key: String,
        work: AlarmDeliveryWork,
    ): AlarmDeliveryJournal.Entry? {
        replaced += work
        return if (replacementSucceeds) AlarmDeliveryJournal.Entry("retry-key", work) else null
    }

    override fun schedule(work: AlarmDeliveryWork, triggerAtEpochMillis: Long): Result<Unit> {
        scheduled += work
        return schedulingResult
    }

    override fun restoreJournalEntry(key: String, work: AlarmDeliveryWork): Boolean {
        restored += work
        return true
    }

    override fun cancel(work: AlarmDeliveryWork): Boolean {
        cancelled += work
        return true
    }
}

private class FakeRecoveryCheckpointPersistence(
    initial: AlarmRecoveryCheckpoint? = null,
) : AlarmRecoveryCheckpointPersistence {
    private var value: AlarmRecoveryCheckpoint? = initial

    override fun checkpoint(): AlarmRecoveryCheckpoint? = value

    override fun persist(checkpoint: AlarmRecoveryCheckpoint): Boolean {
        value = checkpoint
        return true
    }

    override fun clear(): Boolean {
        value = null
        return true
    }
}

private class FakeRecoveryRetryBackend(
    private val result: Result<Unit> = Result.success(Unit),
) : AlarmRecoveryRetryBackend {
    val scheduled = mutableListOf<Long>()
    var cancelCount = 0

    override fun schedule(triggerAtEpochMillis: Long): Result<Unit> {
        scheduled += triggerAtEpochMillis
        return result
    }

    override fun cancel(): Boolean {
        cancelCount += 1
        return true
    }
}

private fun trigger(
    kind: AlarmKind = AlarmKind.START,
    attempt: Int = 0,
): AlarmTrigger = AlarmTrigger(
    kind = kind,
    scheduleId = ScheduleId("schedule-1"),
    scheduleUpdatedAt = Instant.parse("2026-08-09T10:00:00Z"),
    expectedAt = Instant.parse("2026-08-09T11:00:00Z"),
    deliveryAttempt = attempt,
)

private fun entry(trigger: AlarmTrigger): AlarmDeliveryJournal.Entry =
    AlarmDeliveryJournal.Entry("entry-key", AlarmDeliveryWork.Schedule(trigger))
