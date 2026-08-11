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

interface AutomationEngine {
    suspend fun start(sessionId: SessionId): AutomationRunResult

    suspend fun stop(sessionId: SessionId): AutomationRunResult
}

sealed interface AutomationRunResult {
    data class Succeeded(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class AlreadySatisfied(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class AlreadyTerminal(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class StopVerifiedAfterFailure(
        val session: ExecutionSession,
        val originalFailure: AutomationFailure?,
    ) : AutomationRunResult

    data class NotFound(
        val sessionId: SessionId,
    ) : AutomationRunResult

    data class Rejected(
        val session: ExecutionSession,
        val failure: AutomationFailure,
    ) : AutomationRunResult

    data class Failed(
        val session: ExecutionSession,
        val failure: AutomationFailure,
    ) : AutomationRunResult

    /**
     * Record may have reached Pixel Camera, but its recording postcondition is still unverified.
     *
     * Callers must retain delivery and retry START so the engine can reconcile by observation;
     * the write-ahead checkpoint prevents a second Record dispatch.
     */
    data class StartReconciliationRequired(
        val session: ExecutionSession,
        val failure: AutomationFailure,
    ) : AutomationRunResult

    data class RevisionConflict(
        val session: ExecutionSession,
        val expectedRevision: Long,
        val actualRevision: Long?,
    ) : AutomationRunResult

    data class PersistenceFailure(
        val session: ExecutionSession?,
        val failure: AutomationFailure,
    ) : AutomationRunResult
}

class DefaultAutomationEngine(
    executionRepository: ExecutionRepository,
    profileRepository: AutomationProfileRepository,
    deviceControl: DeviceControlPort,
    pixelCamera: PixelCameraPort,
    recordingMedia: RecordingMediaPort,
    clock: LenswakeClock,
    config: AutomationConfig = AutomationConfig.production(),
    sleeper: AutomationSleeper = CoroutineAutomationSleeper,
) : AutomationEngine {
internal val environment = EngineEnvironment(
        executionRepository = executionRepository,
        profileRepository = profileRepository,
        deviceControl = deviceControl,
        pixelCamera = pixelCamera,
        recordingMedia = recordingMedia,
        clock = clock,
        config = config,
        sleeper = sleeper,
    )

    override suspend fun start(sessionId: SessionId): AutomationRunResult = environment.start(sessionId)

    override suspend fun stop(sessionId: SessionId): AutomationRunResult = environment.stop(sessionId)
}

internal class EngineEnvironment(
    val executionRepository: ExecutionRepository,
    val profileRepository: AutomationProfileRepository,
    val deviceControl: DeviceControlPort,
    val pixelCamera: PixelCameraPort,
    val recordingMedia: RecordingMediaPort,
    val clock: LenswakeClock,
    val config: AutomationConfig,
    val sleeper: AutomationSleeper,
)
