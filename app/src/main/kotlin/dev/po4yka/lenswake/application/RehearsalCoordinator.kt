package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.SessionId

fun interface RehearsalCoordinator {
    suspend fun run(request: RehearsalRequest): RehearsalResult
}

sealed interface RehearsalResult {
    data class Completed(
        val session: ExecutionSession,
        val verifiedProfile: PixelCameraProfile,
    ) : RehearsalResult

    data class Busy(val activeSessionId: SessionId) : RehearsalResult

    data class Rejected(
        val code: RehearsalResultCode,
        val message: String,
        val sessionId: SessionId? = null,
    ) : RehearsalResult {
        init {
            require(message.isNotBlank()) { "Rehearsal rejection message must not be blank" }
        }
    }

    data class SafetyStopPending(
        val sessionId: SessionId,
        val message: String,
    ) : RehearsalResult
}

enum class RehearsalResultCode {
    PROFILE_NOT_FOUND,
    ENVIRONMENT_UNAVAILABLE,
    ENVIRONMENT_MISMATCH,
    ACTIVE_REHEARSAL_EXISTS,
    SESSION_PERSISTENCE_FAILED,
    SNAPSHOT_CAPTURE_FAILED,
    BACKSTOP_UNAVAILABLE,
    START_FAILED,
    STOP_FAILED,
    PROMOTION_PROOF_MISSING,
    PROFILE_PROMOTION_FAILED,
    INVALID_STOP_TRIGGER,
}
