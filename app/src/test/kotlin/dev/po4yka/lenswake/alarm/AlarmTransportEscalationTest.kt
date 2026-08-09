package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.ScheduleId
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmTransportEscalationTest {
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

        assertEquals(listOf(trigger), backend.restored)
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

        assertTrue(coordinator.resolve(trigger))

        assertTrue(persistence.markers().isEmpty())
        assertEquals(listOf(AlarmTransportEscalator.deliveryMarkerId(trigger)), notifier.dismissed)
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

private class FakeFailurePersistence : AlarmTransportFailurePersistence {
    private val values = linkedMapOf<String, AlarmTransportFailureMarker>()

    override fun persist(marker: AlarmTransportFailureMarker): Boolean {
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
    val replaced = mutableListOf<AlarmTrigger>()
    val scheduled = mutableListOf<AlarmTrigger>()
    val restored = mutableListOf<AlarmTrigger>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun replaceJournalEntry(
        key: String,
        trigger: AlarmTrigger,
    ): AlarmDeliveryJournal.Entry? {
        replaced += trigger
        return if (replacementSucceeds) AlarmDeliveryJournal.Entry("retry-key", trigger) else null
    }

    override fun schedule(trigger: AlarmTrigger, triggerAtEpochMillis: Long): Result<Unit> {
        scheduled += trigger
        return schedulingResult
    }

    override fun restoreJournalEntry(key: String, trigger: AlarmTrigger): Boolean {
        restored += trigger
        return true
    }
}

private class FakeRecoveryCheckpointPersistence : AlarmRecoveryCheckpointPersistence {
    private var value: AlarmRecoveryCheckpoint? = null

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
    AlarmDeliveryJournal.Entry("entry-key", trigger)
