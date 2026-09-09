package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration

/** Preparation completes before Record's durable write-ahead checkpoint; cancellation never records. */
internal suspend fun EngineEnvironment.prepareCapture(context: RunContext, capture: CaptureConfiguration) {
    val operation = AutomationOperation.PREPARE_CAPTURE
    val state = AutomationStateName.PREPARING_CAPTURE
    context.transition(state, operation = operation, outcome = AutomationOutcome.STARTED)
    val result = safeCall(
        block = {
            when (val call = timed(operation) { pixelCamera.prepareCapture(capture, context.profileUse) }) {
                is TimedCall.Completed -> call.value
                TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
            }
        },
        recover = { error ->
            PortResult.Unavailable(operationFailure(
                AutomationFailureCode.CAMERA_STATE_UNKNOWN, "Capture preparation failed", error,
            ))
        },
    )
    when (result) {
        is PortResult.Unavailable -> fail(context, result.failure)
        is PortResult.Observed -> {
            if (result.value != capture) {
                fail(context, failure(AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "Preparation confirmed a different capture configuration"))
            }
            context.transition(state, operation = operation, outcome = AutomationOutcome.SUCCEEDED,
                metadata = mapOf("captureMode" to capture.mode.name, "lens" to capture.lens.name))
        }
    }
}
