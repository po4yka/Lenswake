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
import kotlin.time.TimeSource

internal fun EngineEnvironment.persistenceFailure(
    message: String,
    error: Exception,
): AutomationFailure = failure(
        AutomationFailureCode.SESSION_PERSISTENCE_FAILED,
        message,
        mapOf(
            "exception" to exceptionName(error),
        ),
    )

internal suspend fun EngineEnvironment.inspectDevice(): PortResult<DeviceState> = safeCall(
    block = {
        when (val timed = timed(AutomationOperation.WAKE_DEVICE, deviceControl::inspect)) {
            is TimedCall.Completed -> timed.value
            TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(AutomationOperation.WAKE_DEVICE))
        }
    },
    recover = { error ->
        PortResult.Unavailable(
            operationFailure(
                AutomationFailureCode.WAKE_FAILED,
                "The device state could not be inspected",
                error,
            ),
        )
    },
)

internal fun EngineEnvironment.operationFailure(
        code: AutomationFailureCode,
        message: String,
        error: Exception,
    ): AutomationFailure = failure(
        code,
        message,
        mapOf("exception" to exceptionName(error)),
    )

internal suspend fun <T> safeCall(
    block: suspend () -> T,
    recover: suspend (Exception) -> T,
): T {
    val result = runCatching { block() }
    val error = result.exceptionOrNull()
    return when (error) {
        null -> result.getOrThrow()
        is CancellationException -> throw error
        is Exception -> recover(error)
        else -> throw error
    }
}

private const val MAX_EXCEPTION_NAME_LENGTH = 256

private fun exceptionName(error: Exception): String =
    (error::class.qualifiedName ?: error::class.simpleName.orEmpty())
        .take(MAX_EXCEPTION_NAME_LENGTH)

internal suspend fun <T> EngineEnvironment.timed(
        operation: AutomationOperation,
        block: suspend () -> T,
    ): TimedCall<T> = withTimeoutOrNull(config.timeoutFor(operation)) {
        val startedAt = TimeSource.Monotonic.markNow()
        val value = block()
        TimedCall.Completed(value, startedAt.elapsedNow().inWholeMilliseconds)
    } ?: TimedCall.TimedOut

internal fun EngineEnvironment.timeoutFailure(operation: AutomationOperation): AutomationFailure = failure(
        AutomationFailureCode.AUTOMATION_TIMEOUT,
        "Automation operation $operation exceeded its finite timeout",
        mapOf(
            "operation" to operation.name,
            "timeoutMs" to config.timeoutFor(operation).inWholeMilliseconds.toString(),
        ),
    )

internal suspend fun EngineEnvironment.validateSupportedCapture(context: RunContext) {
        val capture = context.current.capture
        if (!context.profileUse.profile.supports(capture)) {
            fail(
                context,
                failure(
                    AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION,
                    "The Pixel Camera profile has no verified selectors for the capture configuration",
                    mapOf(
                        "lens" to capture.lens.name,
                        "zoom" to (capture.zoom?.factor?.toString() ?: "none"),
                    ),
                ),
            )
        }
    }

internal fun EngineEnvironment.failure(
        code: AutomationFailureCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ) = AutomationFailure(code, message, context)
