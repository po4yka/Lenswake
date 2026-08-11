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
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supports
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun EngineEnvironment.dispatchRecordingStart(context: RunContext) {
        captureMediaBaseline(context)
        val operation = AutomationOperation.START_RECORDING
        val state = AutomationStateName.STARTING_RECORDING
        val policy = config.policyFor(operation)
        var lastRejection: AutomationFailure? = null

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
                metadata = mapOf("dispatchCheckpoint" to "write_ahead"),
            ) { session, now -> session.copy(recordActionAt = session.recordActionAt ?: now) }

            val invocation = invokeRecordingAction(
                context = context,
                operation = operation,
                failureCode = AutomationFailureCode.RECORD_ACTION_FAILED,
                failureMessage = "Record dispatch threw after a possible external side effect",
            ) { pixelCamera.startRecording(context.current.capture.mode, context.profileUse) }

            when (invocation) {
                TimedCall.TimedOut -> fail(context, timeoutFailure(operation))
                is TimedCall.Completed -> when (val dispatch = invocation.value) {
                    is ActionDispatch.Dispatched -> {
                        context.transitionDispatched(state, operation, attempt, invocation.durationMs, dispatch)
                        return
                    }

                    is ActionDispatch.Rejected -> {
                        lastRejection = dispatch.failure
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.FAILED,
                            attempt = attempt,
                            failure = dispatch.failure,
                            metadata = mapOf("dispatchCheckpoint" to "cleared_definitive_rejection"),
                        ) { session, _ -> session.copy(recordActionAt = null) }
                    }
                }
            }
        }

        fail(
            context,
            lastRejection ?: failure(
                AutomationFailureCode.RECORD_ACTION_FAILED,
                "Pixel Camera definitively rejected the Record action",
            ),
        )
    }

internal suspend fun EngineEnvironment.captureMediaBaseline(context: RunContext) {
        if (context.current.mediaBaselineGeneration != null) return
        val operation = AutomationOperation.CAPTURE_MEDIA_BASELINE
        val state = AutomationStateName.CAPTURING_MEDIA_BASELINE
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
            val result = readMediaBaseline(operation)
            when (result) {
                is PortResult.Observed -> {
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.SUCCEEDED,
                        attempt = attempt,
                        metadata = mapOf(
                            "mediaStoreGeneration" to result.value.generation.toString(),
                            "mediaStoreVersion" to result.value.version,
                        ),
                    ) { session, _ ->
                        session.copy(
                            mediaBaselineGeneration = result.value.generation,
                            mediaStoreVersion = result.value.version,
                        )
                    }
                    return
                }

                is PortResult.Unavailable -> lastFailure = result.failure
            }
        }

        fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                "MediaStore baseline could not be captured",
            ),
        )
    }

internal suspend fun EngineEnvironment.dispatchRecordingStop(context: RunContext) {
        val operation = AutomationOperation.STOP_RECORDING
        val state = AutomationStateName.STOPPING_RECORDING
        val policy = config.policyFor(operation)
        var lastRejection: AutomationFailure? = null

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
                metadata = mapOf("dispatchCheckpoint" to "write_ahead"),
            ) { session, now -> session.copy(stopActionAt = session.stopActionAt ?: now) }

            val invocation = invokeRecordingAction(
                context = context,
                operation = operation,
                failureCode = AutomationFailureCode.STOP_ACTION_FAILED,
                failureMessage = "Stop dispatch threw after a possible external side effect",
            ) { pixelCamera.stopRecording(context.current.capture.mode, context.profileUse) }

            when (invocation) {
                TimedCall.TimedOut -> fail(context, timeoutFailure(operation))
                is TimedCall.Completed -> when (val dispatch = invocation.value) {
                    is ActionDispatch.Dispatched -> {
                        context.transitionDispatched(state, operation, attempt, invocation.durationMs, dispatch)
                        return
                    }

                    is ActionDispatch.Rejected -> {
                        lastRejection = dispatch.failure
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.FAILED,
                            attempt = attempt,
                            failure = dispatch.failure,
                            metadata = mapOf("dispatchCheckpoint" to "cleared_definitive_rejection"),
                        ) { session, _ -> session.copy(stopActionAt = null) }
                    }
                }
            }
        }

        fail(
            context,
            lastRejection ?: failure(
                AutomationFailureCode.STOP_ACTION_FAILED,
                "Pixel Camera definitively rejected the Stop action",
            ),
        )
    }

internal suspend fun EngineEnvironment.dispatch(
        context: RunContext,
        operation: AutomationOperation,
        state: AutomationStateName,
        defaultFailureCode: AutomationFailureCode,
        defaultFailureMessage: String,
        metadata: Map<String, String> = emptyMap(),
        action: suspend () -> ActionDispatch,
        onDispatched: (ExecutionSession, Instant) -> ExecutionSession = { session, _ -> session },
    ): InteractionMethod {
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
                metadata = metadata,
            )
            var durationMs: Long? = null
            val result = safeCall(
                block = {
                    when (val timed = timed(operation, action)) {
                        is TimedCall.Completed -> timed.value.also { durationMs = timed.durationMs }
                        TimedCall.TimedOut -> ActionDispatch.Rejected(timeoutFailure(operation))
                    }
                },
                recover = { error ->
                    ActionDispatch.Rejected(operationFailure(defaultFailureCode, defaultFailureMessage, error))
                },
            )
            when (result) {
                is ActionDispatch.Dispatched -> {
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.DISPATCHED,
                        method = result.method,
                        attempt = attempt,
                        durationMs = durationMs,
                        metadata = metadata + result.metadata,
                        update = onDispatched,
                    )
                    return result.method
                }

                is ActionDispatch.Rejected -> lastFailure = result.failure
            }
        }
        fail(
            context,
            lastFailure ?: failure(defaultFailureCode, defaultFailureMessage),
        )
    }

internal suspend fun EngineEnvironment.observeCamera(
        context: RunContext,
        operation: AutomationOperation,
        state: AutomationStateName,
        failureCode: AutomationFailureCode,
        failureMessage: String,
        predicate: (PixelCameraState) -> Boolean,
    ): PixelCameraState {
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
            val inspection = inspectCameraHandlingDialog(
                context = context,
                operation = operation,
                failureCode = failureCode,
                failureMessage = failureMessage,
            )
            when (val observed = inspection) {
                is PortResult.Observed -> {
                    if (predicate(observed.value)) {
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.SUCCEEDED,
                            attempt = attempt,
                        )
                        return observed.value
                    }
                }

                is PortResult.Unavailable -> lastFailure = observed.failure
            }
        }
        fail(context, lastFailure ?: failure(failureCode, failureMessage))
    }

internal suspend fun EngineEnvironment.inspectCameraHandlingDialog(
    context: RunContext,
    operation: AutomationOperation,
    failureCode: AutomationFailureCode,
    failureMessage: String,
): PortResult<PixelCameraState> {
    val inspection = safeCall(
        block = {
            when (val timed = timed(operation) { pixelCamera.inspect(context.profileUse) }) {
                is TimedCall.Completed -> timed.value
                TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
            }
        },
        recover = { error ->
            PortResult.Unavailable(operationFailure(failureCode, failureMessage, error))
        },
    )
    return when (inspection) {
        is PortResult.Observed -> when (val state = inspection.value) {
            is PixelCameraState.Dialog -> PortResult.Observed(recoverCameraDialog(context, state.kind))
            else -> inspection
        }
        is PortResult.Unavailable -> inspection
    }
}

private suspend fun EngineEnvironment.recoverCameraDialog(
    context: RunContext,
    dialog: PixelCameraDialogKind,
): PixelCameraState {
    val operation = AutomationOperation.RECOVER_CAMERA_DIALOG
    val metadata = mapOf(
        "dialog" to dialog.name,
        "profileId" to context.profileUse.profile.id.value,
    )
    dispatch(
        context = context,
        operation = operation,
        state = AutomationStateName.RECOVERING_CAMERA_DIALOG,
        defaultFailureCode = AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG,
        defaultFailureMessage = "Pixel Camera dialog $dialog has no safe recovery",
        metadata = metadata,
        action = { pixelCamera.recoverDialog(dialog, context.profileUse) },
    )

    val policy = config.policyFor(operation)
    var lastFailure: AutomationFailure? = null
    for (attempt in 1..policy.maxAttempts) {
        if (attempt > 1) {
            retryTransition(
                context,
                operation,
                attempt,
                AutomationStateName.VERIFYING_CAMERA_DIALOG_RECOVERY,
            )
            sleeper.sleep(policy.delayBeforeAttempt(attempt))
        }
        context.transition(
            state = AutomationStateName.VERIFYING_CAMERA_DIALOG_RECOVERY,
            operation = operation,
            outcome = AutomationOutcome.STARTED,
            attempt = attempt,
            metadata = metadata,
        )
        val inspection = safeCall(
            block = {
                when (val timed = timed(operation) { pixelCamera.inspect(context.profileUse) }) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            },
            recover = { error ->
                PortResult.Unavailable(
                    operationFailure(
                        AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG,
                        "Pixel Camera dialog recovery could not be verified",
                        error,
                    ),
                )
            },
        )
        when (inspection) {
            is PortResult.Observed -> when (val value = inspection.value) {
                is PixelCameraState.Dialog -> {
                    lastFailure = null
                    if (value.kind != dialog) {
                        fail(
                            context,
                            failure(
                                AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG,
                                "A different Pixel Camera dialog appeared during recovery",
                                metadata + ("observedDialog" to value.kind.name),
                            ),
                        )
                    }
                }
                else -> {
                    context.transition(
                        state = AutomationStateName.VERIFYING_CAMERA_DIALOG_RECOVERY,
                        operation = operation,
                        outcome = AutomationOutcome.SUCCEEDED,
                        attempt = attempt,
                        metadata = metadata,
                    )
                    return value
                }
            }

            is PortResult.Unavailable -> lastFailure = inspection.failure
        }
    }
    fail(
        context,
        lastFailure ?: failure(
                AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG,
                "Pixel Camera dialog remained visible after bounded recovery",
                metadata,
            ),
    )
}

internal suspend fun EngineEnvironment.retryTransition(
        context: RunContext,
        operation: AutomationOperation,
        attempt: Int,
        returnState: AutomationStateName,
    ) {
        context.transition(
            state = AutomationStateName.RETRYING,
            operation = operation,
            outcome = AutomationOutcome.RETRYING,
            attempt = attempt,
            metadata = mapOf("returnState" to returnState.name),
        )
    }

internal suspend fun EngineEnvironment.fail(
        context: RunContext,
        failure: AutomationFailure,
    ): Nothing {
        context.transition(
            state = AutomationStateName.FAILED,
            status = SessionStatus.FAILED,
            outcome = AutomationOutcome.FAILED,
            failure = failure,
        ) { session, _ -> session.copy(failure = failure) }
        throw EngineAbort(AutomationRunResult.Failed(context.current, failure))
}

private suspend fun EngineEnvironment.invokeRecordingAction(
    context: RunContext,
    operation: AutomationOperation,
    failureCode: AutomationFailureCode,
    failureMessage: String,
    action: suspend () -> ActionDispatch,
): TimedCall<ActionDispatch> = safeCall(
    block = { timed(operation, action) },
    recover = { error -> fail(context, operationFailure(failureCode, failureMessage, error)) },
)

private suspend fun EngineEnvironment.readMediaBaseline(
    operation: AutomationOperation,
): PortResult<RecordingMediaBaseline> = safeCall(
    block = {
        when (val invocation = timed(operation, recordingMedia::captureBaseline)) {
            is TimedCall.Completed -> invocation.value
            TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
        }
    },
    recover = { error ->
        PortResult.Unavailable(
            operationFailure(
                AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                "MediaStore baseline could not be captured",
                error,
            ),
        )
    },
)
