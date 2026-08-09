package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.AlarmKind
import dev.po4yka.lenswake.alarm.AlarmTrigger
import dev.po4yka.lenswake.alarm.AlarmTriggerCoordinator
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException

/** Validates exact-alarm identity and bridges a persisted execution plan into the engine. */
class DefaultAlarmTriggerCoordinator(
    private val scheduleRepository: ScheduleRepository,
    private val executionRepository: ExecutionRepository,
    private val automationEngine: AutomationEngine,
    private val clock: LenswakeClock,
) : AlarmTriggerCoordinator {
    override suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult {
        val schedule = try {
            scheduleRepository.get(trigger.scheduleId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return rejected("Could not load the alarm schedule", error)
        } ?: return rejected("The alarm schedule no longer exists")

        validateTrigger(trigger, schedule)?.let { return it }
        return when (trigger.kind) {
            AlarmKind.START -> handleStart(schedule)
            AlarmKind.STOP -> handleStop(schedule)
        }
    }

    private suspend fun handleStart(schedule: RecordingSchedule): AlarmHandlingResult {
        val executionKey = executionKey(schedule)
        val sessionId = deterministicSessionId(executionKey)
        val now = clock.now()
        val lookup = loadExecution(sessionId)
        if (lookup.isFailure) {
            return rejected("Could not load the execution session", lookup.exceptionOrNull())
        }
        val existing = lookup.getOrNull() ?: run {
            val session = ExecutionSession(
                id = sessionId,
                executionKey = executionKey,
                kind = SessionKind.SCHEDULED,
                scheduleId = schedule.id,
                scheduleName = schedule.name,
                profileId = schedule.profileId,
                capture = schedule.capture,
                expectedStartAt = schedule.startAt,
                expectedStopAt = schedule.stopAt,
                alarmStartDeliveredAt = now,
                status = SessionStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
            try {
                executionRepository.create(session)
                session
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A concurrent duplicate may have won the unique ID/execution-key insert.
                val winnerLookup = loadExecution(sessionId)
                if (winnerLookup.isFailure) {
                    return rejected("Could not resolve a concurrent execution insert", winnerLookup.exceptionOrNull())
                }
                val winner = winnerLookup.getOrNull()
                    ?: return rejected("Could not persist the execution session", error)
                winner
            }
        }
        validateExecution(existing, schedule, executionKey)?.let { return it }
        return runEngine(AlarmKind.START) { automationEngine.start(existing.id) }
    }

    private suspend fun handleStop(schedule: RecordingSchedule): AlarmHandlingResult {
        val executionKey = executionKey(schedule)
        val deterministicId = deterministicSessionId(executionKey)
        val active = try {
            executionRepository.findActiveForSchedule(schedule.id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return rejected("Could not locate the active execution session", error)
        }
        val deterministicLookup = if (active == null) loadExecution(deterministicId) else null
        if (deterministicLookup?.isFailure == true) {
            return rejected(
                "Could not load the execution for this STOP alarm",
                deterministicLookup.exceptionOrNull(),
            )
        }
        val session = active ?: deterministicLookup?.getOrNull()
            ?: return rejected("No persisted execution exists for this STOP alarm")
        validateExecution(session, schedule, executionKey)?.let { return it }
        val deliveredSession = when (val delivery = persistStopDelivery(session)) {
            is StopDeliveryResult.Persisted -> delivery.session
            is StopDeliveryResult.Failed -> return delivery.result
        }
        return runEngine(AlarmKind.STOP) { automationEngine.stop(deliveredSession.id) }
    }

    private suspend fun persistStopDelivery(session: ExecutionSession): StopDeliveryResult {
        if (session.alarmStopDeliveredAt != null) return StopDeliveryResult.Persisted(session)
        if (session.revision == Long.MAX_VALUE) {
            return StopDeliveryResult.Failed(rejected("STOP delivery cannot increment the execution revision"))
        }

        val deliveredAt = maxOf(clock.now(), session.updatedAt)
        val updated = session.copy(
            alarmStopDeliveredAt = deliveredAt,
            revision = session.revision + 1,
            updatedAt = deliveredAt,
        )
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = STOP_DELIVERED_EVENT,
            sequence = updated.revision,
            timestamp = deliveredAt,
            state = AutomationStateName.STOP_TRIGGERED,
            outcome = AutomationOutcome.SUCCEEDED,
            metadata = mapOf("alarmKind" to AlarmKind.STOP.name),
        )
        val result = try {
            executionRepository.apply(
                change = ExecutionChange(session.revision, updated),
                event = event,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return StopDeliveryResult.Failed(rejected("Could not persist STOP alarm delivery", error))
        }
        return when (result) {
            is ExecutionApplyResult.Applied -> StopDeliveryResult.Persisted(result.session)
            is ExecutionApplyResult.RevisionConflict -> {
                val winner = loadExecution(session.id)
                if (winner.isFailure) {
                    StopDeliveryResult.Failed(
                        rejected("Could not resolve concurrent STOP delivery", winner.exceptionOrNull()),
                    )
                } else {
                    val current = winner.getOrNull()
                    if (current?.alarmStopDeliveredAt != null) {
                        StopDeliveryResult.Persisted(current)
                    } else {
                        StopDeliveryResult.Failed(rejected("STOP delivery lost a concurrent state transition"))
                    }
                }
            }
        }
    }

    private fun validateTrigger(
        trigger: AlarmTrigger,
        schedule: RecordingSchedule,
    ): AlarmHandlingResult.Rejected? {
        if (!schedule.enabled) return rejected("The alarm schedule is disabled")
        if (schedule.updatedAt != trigger.scheduleUpdatedAt) {
            return rejected("The alarm belongs to an obsolete schedule revision")
        }
        val expected = when (trigger.kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        if (expected != trigger.expectedAt) {
            return rejected("The alarm expected time does not match the persisted schedule")
        }
        val now = clock.now()
        if (now.isBefore(expected)) return rejected("The alarm was delivered before its expected time")
        if (trigger.kind == AlarmKind.START && !now.isBefore(schedule.stopAt)) {
            return rejected("The START alarm arrived after the scheduled stop time")
        }
        return null
    }

    private fun validateExecution(
        session: ExecutionSession,
        schedule: RecordingSchedule,
        executionKey: String,
    ): AlarmHandlingResult.Rejected? {
        if (
            session.executionKey != executionKey ||
            session.scheduleId != schedule.id ||
            session.profileId != schedule.profileId ||
            session.expectedStartAt != schedule.startAt ||
            session.expectedStopAt != schedule.stopAt ||
            session.capture != schedule.capture
        ) {
            return rejected("The persisted execution does not match the current schedule intent")
        }
        return null
    }

    private suspend fun loadExecution(id: SessionId): Result<ExecutionSession?> = try {
        Result.success(executionRepository.get(id))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private fun mapEngineResult(
        result: AutomationRunResult,
        kind: AlarmKind,
    ): AlarmHandlingResult = when (result) {
        is AutomationRunResult.Succeeded,
        is AutomationRunResult.AlreadySatisfied,
        is AutomationRunResult.StopVerifiedAfterFailure,
        -> AlarmHandlingResult.Accepted

        is AutomationRunResult.AlreadyTerminal -> AlarmHandlingResult.Accepted
        is AutomationRunResult.NotFound -> rejected("The persisted execution disappeared before automation")
        is AutomationRunResult.Rejected -> rejected("$kind automation was rejected: ${result.failure.message}")
        is AutomationRunResult.Failed -> rejected("$kind automation failed: ${result.failure.message}")
        is AutomationRunResult.RevisionConflict -> rejected(
            "$kind automation lost a concurrent state transition",
        )
        is AutomationRunResult.PersistenceFailure -> rejected(
            "$kind automation could not persist its state: ${result.failure.message}",
        )
    }

    private suspend fun runEngine(
        kind: AlarmKind,
        run: suspend () -> AutomationRunResult,
    ): AlarmHandlingResult = try {
        mapEngineResult(run(), kind)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        rejected("$kind automation terminated unexpectedly", error)
    }

    private fun executionKey(schedule: RecordingSchedule): String =
        "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}"

    private fun deterministicSessionId(executionKey: String): SessionId = SessionId(
        UUID.nameUUIDFromBytes(executionKey.toByteArray(StandardCharsets.UTF_8)).toString(),
    )

    private fun rejected(
        reason: String,
        cause: Throwable? = null,
    ): AlarmHandlingResult.Rejected = AlarmHandlingResult.Rejected(reason, cause)

    private sealed interface StopDeliveryResult {
        data class Persisted(val session: ExecutionSession) : StopDeliveryResult
        data class Failed(val result: AlarmHandlingResult.Rejected) : StopDeliveryResult
    }

    private companion object {
        const val STOP_DELIVERED_EVENT = "automation.alarm.stop_delivered"
    }
}
