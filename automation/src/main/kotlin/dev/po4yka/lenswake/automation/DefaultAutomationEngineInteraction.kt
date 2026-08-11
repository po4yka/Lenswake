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

internal suspend fun EngineEnvironment.ensureInteractive(
        context: RunContext,
        state: AutomationStateName,
    ) {
        context.transition(
            state = state,
            operation = AutomationOperation.WAKE_DEVICE,
            outcome = AutomationOutcome.STARTED,
        )
        val initial = inspectDevice()
        if (initial is PortResult.Observed && initial.value.interactive) return

        dispatch(
            context = context,
            operation = AutomationOperation.WAKE_DEVICE,
            state = state,
            defaultFailureCode = AutomationFailureCode.WAKE_FAILED,
            defaultFailureMessage = "The device rejected the wake action",
            action = deviceControl::wake,
        )

        val policy = config.policyFor(AutomationOperation.WAKE_DEVICE)
        var lastFailure: AutomationFailure? = (initial as? PortResult.Unavailable)?.failure
        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, AutomationOperation.WAKE_DEVICE, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            when (val observed = inspectDevice()) {
                is PortResult.Observed -> if (observed.value.interactive) return
                is PortResult.Unavailable -> lastFailure = observed.failure
            }
        }
        fail(
            context,
            lastFailure ?: failure(AutomationFailureCode.WAKE_FAILED, "The device did not become interactive"),
        )
    }

internal suspend fun EngineEnvironment.launchAndObserveCamera(
        context: RunContext,
        launchState: AutomationStateName = AutomationStateName.LAUNCHING_SECURE_CAMERA,
    ): PixelCameraState {
        dispatch(
            context = context,
            operation = AutomationOperation.LAUNCH_CAMERA,
            state = launchState,
            defaultFailureCode = AutomationFailureCode.PIXEL_CAMERA_LAUNCH_FAILED,
            defaultFailureMessage = "Secure Pixel Camera launch was rejected",
            action = { pixelCamera.launchSecureCamera(context.profileUse) },
        )
        return observeCamera(
            context = context,
            operation = AutomationOperation.INSPECT_CAMERA,
            state = if (launchState == AutomationStateName.LOCATING_PIXEL_CAMERA) {
                AutomationStateName.LOCATING_PIXEL_CAMERA
            } else {
                AutomationStateName.WAITING_FOR_PIXEL_CAMERA
            },
            failureCode = AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
            failureMessage = "Pixel Camera did not become observable after launch",
        ) { it !is PixelCameraState.NotRunning && it !is PixelCameraState.Unknown }
    }

internal suspend fun EngineEnvironment.dispatchAndVerify(
        context: RunContext,
        operation: AutomationOperation,
        actionState: AutomationStateName,
        verificationState: AutomationStateName,
        dispatchFailure: AutomationFailure,
        verificationFailure: AutomationFailure,
        action: suspend () -> ActionDispatch,
        predicate: (PixelCameraState) -> Boolean,
    ) {
        dispatch(
            context = context,
            operation = operation,
            state = actionState,
            defaultFailureCode = dispatchFailure.code,
            defaultFailureMessage = dispatchFailure.message,
            action = action,
        )
        observeCamera(
            context = context,
            operation = operation,
            state = verificationState,
            failureCode = verificationFailure.code,
            failureMessage = verificationFailure.message,
            predicate = predicate,
        )
    }

    /**
     * The speed-picker opener is idempotent: a dispatched gesture may be ignored while Pixel
     * Camera finishes a cold launch, so convergence retries the opener rather than only polling.
     * This is deliberately not shared with Record or Stop, whose dispatched actions are unsafe to
     * repeat until their external side effects have been reconciled.
     */
internal suspend fun EngineEnvironment.openTimeLapseSpeedControlAndVerify(
    context: RunContext,
    dispatchFailure: AutomationFailure,
    verificationFailure: AutomationFailure,
    predicate: (PixelCameraState) -> Boolean,
) {
    val operation = AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL
    val actionState = AutomationStateName.OPENING_TIME_LAPSE_SPEED_CONTROL
    val policy = config.policyFor(operation)
    var lastFailure: AutomationFailure? = null
    for (attempt in 1..policy.maxAttempts) {
        if (attempt > 1) {
            retryTransition(context, operation, attempt, actionState)
            sleeper.sleep(policy.delayBeforeAttempt(attempt))
        }
        val beforeDispatch = inspectSpeedPicker(context, attempt, verificationFailure, predicate)
        if (beforeDispatch.open) return
        lastFailure = beforeDispatch.failure ?: lastFailure

        val dispatch = dispatchSpeedPickerOpen(context, attempt, dispatchFailure)
        if (dispatch is ActionDispatch.Rejected) {
            lastFailure = dispatch.failure
            continue
        }
        val afterDispatch = inspectSpeedPicker(context, attempt, verificationFailure, predicate)
        if (afterDispatch.open) return
        lastFailure = afterDispatch.failure ?: lastFailure
    }
    fail(context, lastFailure ?: verificationFailure)
}

private data class PickerInspection(
    val open: Boolean,
    val failure: AutomationFailure? = null,
)

private suspend fun EngineEnvironment.inspectSpeedPicker(
    context: RunContext,
    attempt: Int,
    verificationFailure: AutomationFailure,
    predicate: (PixelCameraState) -> Boolean,
): PickerInspection {
    val operation = AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL
    val state = AutomationStateName.VERIFYING_TIME_LAPSE_SPEED_CONTROL
    context.transition(state, operation = operation, outcome = AutomationOutcome.STARTED, attempt = attempt)
    val inspection = inspectCameraHandlingDialog(
        context = context,
        operation = operation,
        failureCode = verificationFailure.code,
        failureMessage = verificationFailure.message,
    )
    return when (inspection) {
        is PortResult.Observed -> PickerInspection(predicate(inspection.value)).also { result ->
            if (result.open) {
                context.transition(
                    state,
                    operation = operation,
                    outcome = AutomationOutcome.SUCCEEDED,
                    attempt = attempt,
                )
            }
        }
        is PortResult.Unavailable -> PickerInspection(open = false, failure = inspection.failure)
    }
}

private suspend fun EngineEnvironment.dispatchSpeedPickerOpen(
    context: RunContext,
    attempt: Int,
    dispatchFailure: AutomationFailure,
): ActionDispatch {
    val operation = AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL
    val state = AutomationStateName.OPENING_TIME_LAPSE_SPEED_CONTROL
    context.transition(state, operation = operation, outcome = AutomationOutcome.STARTED, attempt = attempt)
    var durationMs: Long? = null
    val dispatch = safeCall(
        block = {
            when (val timed = timed(operation) { pixelCamera.openTimeLapseSpeedControl(context.profileUse) }) {
                is TimedCall.Completed -> timed.value.also { durationMs = timed.durationMs }
                TimedCall.TimedOut -> ActionDispatch.Rejected(timeoutFailure(operation))
            }
        },
        recover = { error ->
            ActionDispatch.Rejected(operationFailure(dispatchFailure.code, dispatchFailure.message, error))
        },
    )
    if (dispatch is ActionDispatch.Dispatched) {
        context.transition(
            state,
            operation = operation,
            outcome = AutomationOutcome.DISPATCHED,
            method = dispatch.method,
            attempt = attempt,
            durationMs = durationMs,
            metadata = dispatch.metadata,
        )
    }
    return dispatch
}
