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

internal suspend fun EngineEnvironment.stop(sessionId: SessionId): AutomationRunResult =
    execute(sessionId) { context ->
        val disposition = stopDisposition(context.current)
        val earlyResult = resumeMediaVerification(context, disposition) ?: stopEligibility(context)
        earlyResult ?: performStop(context, disposition)
    }

private data class StopDisposition(
    val preserveFailedOutcome: Boolean,
    val preservedFailure: AutomationFailure?,
    val uncertainStopAtEntry: Boolean,
)

private fun EngineEnvironment.stopDisposition(session: ExecutionSession): StopDisposition {
    val retryingOnlyMediaVerification = session.status == SessionStatus.FAILED &&
        session.failure?.code == AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED &&
        session.stoppedVerifiedAt != null && session.recordingVerifiedAt != null
    val preserveFailure =
        (session.status == SessionStatus.FAILED && !retryingOnlyMediaVerification) ||
            session.recordingVerifiedAt == null
    val fallback = failure(
        AutomationFailureCode.RECORDING_NOT_CONFIRMED,
        "A dispatched recording was never verified before safe stop recovery",
    )
    return StopDisposition(
        preserveFailedOutcome = preserveFailure,
        preservedFailure = session.failure ?: fallback.takeIf { preserveFailure },
        uncertainStopAtEntry = session.stopActionAt != null,
    )
}

private suspend fun EngineEnvironment.resumeMediaVerification(
    context: RunContext,
    disposition: StopDisposition,
): AutomationRunResult? {
    if (context.current.stoppedVerifiedAt == null || context.current.mediaSavedVerifiedAt != null) return null
    val operation = if (context.current.mediaVerificationRequired) {
        verifySavedRecording(context)
        AutomationOperation.VERIFY_MEDIA_SAVED
    } else {
        AutomationOperation.VERIFY_STOPPED
    }
    return finishVerifiedStop(
        context,
        disposition.preserveFailedOutcome,
        disposition.preservedFailure,
        completionOperation = operation,
    )
}

private fun EngineEnvironment.stopEligibility(context: RunContext): AutomationRunResult? {
    val session = context.current
    val hasOwnership = session.recordActionAt != null && session.cameraOwnershipReleasedAt == null
    val stopOutstanding = session.stoppedVerifiedAt == null
    val terminal = session.status == SessionStatus.COMPLETED ||
        ((session.status == SessionStatus.CANCELLED || session.status == SessionStatus.FAILED) &&
            (!hasOwnership || !stopOutstanding))
    return when {
        terminal || !stopOutstanding -> AutomationRunResult.AlreadyTerminal(session)
        !hasOwnership -> rejectedState(session, "Cannot stop a recording that Lenswake did not dispatch")
        else -> null
    }
}

private suspend fun EngineEnvironment.performStop(
    context: RunContext,
    disposition: StopDisposition,
): AutomationRunResult {
    context.profileUse = loadProfileUse(context)
    prepareStop(context)
    var beforeStop = locateRecording(context)
    val capture = context.current.capture
    if (disposition.uncertainStopAtEntry && beforeStop.isOwnedOrUnknownRecording(capture)) {
        beforeStop = reconcileUncertainStop(context, capture)
    }
    dispatchStopIfRequired(context, beforeStop, capture)
    verifyStopped(context)
    val completionOperation = if (context.current.mediaVerificationRequired) {
        verifySavedRecording(context)
        AutomationOperation.VERIFY_MEDIA_SAVED
    } else {
        AutomationOperation.VERIFY_STOPPED
    }
    return finishVerifiedStop(
        context,
        disposition.preserveFailedOutcome,
        disposition.preservedFailure,
        completionOperation,
    )
}

internal suspend fun EngineEnvironment.finishVerifiedStop(
        context: RunContext,
        preserveFailedOutcome: Boolean,
        preservedFailure: AutomationFailure?,
        completionOperation: AutomationOperation = AutomationOperation.VERIFY_MEDIA_SAVED,
    ): AutomationRunResult =
        if (preserveFailedOutcome) {
            context.transition(
                state = AutomationStateName.FAILED,
                status = SessionStatus.FAILED,
                operation = completionOperation,
                outcome = AutomationOutcome.SUCCEEDED,
                metadata = mapOf("recovery" to "dispatched_but_unverified_recording"),
            ) { session, _ -> session.copy(failure = preservedFailure) }
            AutomationRunResult.StopVerifiedAfterFailure(context.current, preservedFailure)
        } else {
            context.transition(
                state = AutomationStateName.COMPLETED,
                status = SessionStatus.COMPLETED,
                operation = completionOperation,
                outcome = AutomationOutcome.SUCCEEDED,
            ) { session, _ -> session.copy(failure = null) }
            AutomationRunResult.Succeeded(context.current)
        }

internal suspend fun EngineEnvironment.verifySavedRecording(context: RunContext) {
        val baseline = requiredMediaBaseline(context)
        val operation = AutomationOperation.VERIFY_MEDIA_SAVED
        val state = AutomationStateName.VERIFYING_MEDIA_SAVED
        val policy = config.policyFor(operation)
        var lastFailure: AutomationFailure? = null

        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            context.transition(
                state = state,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
            )
            val result = querySavedRecording(baseline, operation)
            when (result) {
                is PortResult.Observed -> result.value?.let { evidence ->
                    if (evidence.generationAdded <= baseline.generation) {
                        lastFailure = failure(
                            AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                            "MediaStore returned video evidence that does not follow the recording baseline",
                            mapOf(
                                "baselineGeneration" to baseline.generation.toString(),
                                "candidateGeneration" to evidence.generationAdded.toString(),
                            ),
                        )
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.FAILED,
                            attempt = attempt,
                            failure = lastFailure,
                        )
                        return@let
                    }
                    context.persistSavedEvidence(evidence, attempt)
                    return
                }

                is PortResult.Unavailable -> lastFailure = result.failure
            }
        }

        fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                "No published Pixel Camera video appeared after the recording baseline",
                mapOf("baselineGeneration" to baseline.generation.toString()),
            ),
        )
    }

private suspend fun EngineEnvironment.requiredMediaBaseline(context: RunContext): RecordingMediaBaseline {
    val generation = context.current.mediaBaselineGeneration ?: fail(
        context,
        failure(
            AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
            "Recording output cannot be verified without a pre-Record MediaStore baseline",
        ),
    )
    val version = context.current.mediaStoreVersion ?: fail(
        context,
        failure(
            AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
            "Recording output cannot be verified without the baseline MediaStore version",
        ),
    )
    return RecordingMediaBaseline(generation, version)
}

private suspend fun EngineEnvironment.querySavedRecording(
    baseline: RecordingMediaBaseline,
    operation: AutomationOperation,
): PortResult<SavedRecordingEvidence?> = safeCall(
    block = {
        when (val invocation = timed(operation) { recordingMedia.findSavedRecording(baseline) }) {
            is TimedCall.Completed -> invocation.value
            TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
        }
    },
    recover = { error ->
        PortResult.Unavailable(
            operationFailure(
                AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                "Saved recording could not be queried from MediaStore",
                error,
            ),
        )
    },
)

private suspend fun RunContext.persistSavedEvidence(
    evidence: SavedRecordingEvidence,
    attempt: Int,
) {
    transition(
        state = AutomationStateName.VERIFYING_MEDIA_SAVED,
        operation = AutomationOperation.VERIFY_MEDIA_SAVED,
        outcome = AutomationOutcome.SUCCEEDED,
        attempt = attempt,
        metadata = mapOf(
            "mediaStoreGeneration" to evidence.generationAdded.toString(),
            "sizeBytes" to evidence.sizeBytes.toString(),
            "durationMs" to evidence.durationMillis.toString(),
        ),
    ) { session, now ->
        session.copy(mediaSavedVerifiedAt = now, savedMediaGeneration = evidence.generationAdded)
    }
}

internal suspend fun EngineEnvironment.reconcileUncertainStop(
        context: RunContext,
        capture: CaptureConfiguration,
    ): PixelCameraState {
        val operation = AutomationOperation.VERIFY_STOPPED
        val state = AutomationStateName.VERIFYING_STOPPED
        val policy = config.policyFor(operation)
        var lastObserved: PixelCameraState? = null
        var lastFailure: AutomationFailure? = null

        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            context.transition(
                state = state,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
                metadata = mapOf("reconciliation" to "uncertain_stop"),
            )
            val inspection = inspectDuringStopReconciliation(context, operation)
            when (inspection) {
                is PortResult.Observed -> {
                    lastObserved = inspection.value
                    if (inspection.value.isConfirmedStopped()) {
                        markUncertainStopReconciled(context, operation, attempt)
                        return inspection.value
                    }
                }

                is PortResult.Unavailable -> {
                    lastObserved = null
                    lastFailure = inspection.failure
                }
            }
        }

        val confirmedRecording = lastObserved?.takeIf {
            it.isConfirmedRecording(capture) || it is PixelCameraState.RecordingUnknownMode
        } ?: fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.STOP_NOT_CONFIRMED,
                "An uncertain Stop could not be reconciled to a safe camera state",
            ),
        )
        markStopReadyForRedispatch(context, operation, policy.maxAttempts)
        return confirmedRecording
    }
