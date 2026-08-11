package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supports
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun EngineEnvironment.execute(
    sessionId: SessionId,
    workflow: suspend (RunContext) -> AutomationRunResult,
): AutomationRunResult = try {
    val session = safeCall(
        block = { executionRepository.get(sessionId) },
        recover = { error ->
            throw EngineAbort(
                AutomationRunResult.PersistenceFailure(
                    session = null,
                    failure = persistenceFailure("Could not load execution session", error),
                ),
            )
        },
    )
    if (session == null) AutomationRunResult.NotFound(sessionId) else workflow(RunContext(session, this))
} catch (abort: EngineAbort) {
    abort.result
}

internal class RunContext(
    var current: ExecutionSession,
    internal val environment: EngineEnvironment,
) {
        lateinit var profileUse: ProfileUse
        var configuredLensObservedBeforeSpeedPicker: Boolean = false

        suspend fun transition(
            state: AutomationStateName,
            status: SessionStatus = current.status,
            operation: AutomationOperation? = null,
            outcome: AutomationOutcome,
            method: InteractionMethod? = null,
            attempt: Int? = null,
            failure: AutomationFailure? = null,
            metadata: Map<String, String> = emptyMap(),
            update: (ExecutionSession, Instant) -> ExecutionSession = { session, _ -> session },
        ) {
            ensureRevisionCanAdvance()
            val now = maxOf(environment.clock.now(), current.updatedAt)
            val nextRevision = current.revision + 1
            val base = current.copy(
                status = status,
                currentAutomationState = state,
                revision = nextRevision,
                updatedAt = now,
            )
            val updated = update(base, now).copy(revision = nextRevision, updatedAt = now)
            val event = AutomationEvent(
                id = EventId.new(),
                sessionId = current.id,
                name = environment.eventName(state, operation, outcome),
                sequence = nextRevision,
                timestamp = now,
                state = state,
                operation = operation,
                outcome = outcome,
                interactionMethod = method,
                attempt = attempt,
                failure = failure,
                metadata = metadata,
            )
            val result = safeCall(
                block = {
                    environment.executionRepository.apply(
                        change = ExecutionChange(current.revision, updated),
                        event = event,
                    )
                },
                recover = { error ->
                    throw EngineAbort(
                        AutomationRunResult.PersistenceFailure(
                            current,
                            environment.persistenceFailure(
                                "Could not persist automation transition $state",
                                error,
                            ),
                        ),
                    )
                },
            )
            when (result) {
                is ExecutionApplyResult.Applied -> current = result.session
                is ExecutionApplyResult.RevisionConflict -> throw EngineAbort(
                    AutomationRunResult.RevisionConflict(
                        session = current,
                        expectedRevision = result.expectedRevision,
                        actualRevision = result.actualRevision,
                    ),
                )
            }
        }

    private fun ensureRevisionCanAdvance() {
        if (current.revision == Long.MAX_VALUE) {
            throw EngineAbort(
                AutomationRunResult.PersistenceFailure(
                    current,
                    environment.failure(
                        AutomationFailureCode.SESSION_PERSISTENCE_FAILED,
                        "Execution revision cannot be incremented",
                    ),
                ),
            )
        }
    }
}

internal fun EngineEnvironment.rejectedState(
    session: ExecutionSession,
    message: String,
): AutomationRunResult.Rejected =
        AutomationRunResult.Rejected(
            session,
            failure(AutomationFailureCode.SESSION_STATE_CONFLICT, message),
        )

internal suspend fun EngineEnvironment.loadProfileUse(context: RunContext): ProfileUse {
        val profile = safeCall(
            block = { profileRepository.get(context.current.profileId) },
            recover = { error ->
                throw EngineAbort(
                    AutomationRunResult.PersistenceFailure(
                        context.current,
                        persistenceFailure("Could not load Pixel Camera profile", error),
                    ),
                )
            },
        ) ?: profileFailure(
            context,
            failure(
                AutomationFailureCode.PROFILE_NOT_FOUND,
                "Pixel Camera profile ${context.current.profileId.value} was not found",
            ),
        )

        when (profile.compatibility) {
            ProfileCompatibility.INCOMPATIBLE -> profileFailure(
                context,
                failure(
                    AutomationFailureCode.PROFILE_INCOMPATIBLE,
                    "Pixel Camera profile is marked incompatible",
                ),
            )

            ProfileCompatibility.NEEDS_REHEARSAL,
            ProfileCompatibility.PROBABLY_COMPATIBLE,
            -> if (context.current.kind == SessionKind.SCHEDULED) {
                profileFailure(
                    context,
                    failure(
                        AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL,
                        "Unattended execution requires a verified Pixel Camera profile",
                    ),
                )
            }

            ProfileCompatibility.VERIFIED -> Unit
        }
        return ProfileUse(
            profile = profile,
            kind = when (context.current.kind) {
                SessionKind.SCHEDULED -> ProfileUse.Kind.UNATTENDED
                SessionKind.REHEARSAL -> ProfileUse.Kind.REHEARSAL
            },
        )
    }

internal suspend fun EngineEnvironment.profileFailure(
        context: RunContext,
        failure: AutomationFailure,
    ): Nothing {
        if (context.current.status == SessionStatus.FAILED) {
            throw EngineAbort(AutomationRunResult.Rejected(context.current, failure))
        }
        fail(context, failure)
    }
