package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleValidation
import dev.po4yka.lenswake.core.ScheduleValidationError
import dev.po4yka.lenswake.core.ScheduleValidator
import dev.po4yka.lenswake.core.supports
import java.time.Instant

internal class ScheduleReadiness(
    private val profileRepository: AutomationProfileRepository,
    private val executionRepository: ExecutionRepository,
    private val preflightProbe: RuntimePreflightProbe,
    private val validator: ScheduleValidator,
) {
    suspend fun failure(
        schedule: RecordingSchedule,
        now: Instant,
    ): ScheduleWorkflowResult? = validationFailure(schedule, now) ?: profileFailure(schedule)

    private fun validationFailure(
        schedule: RecordingSchedule,
        now: Instant,
    ): ScheduleWorkflowResult.Rejected? {
        val validation = if (schedule.enabled) {
            validator.validateForScheduling(schedule, now)
        } else {
            validator.validateForPersistence(schedule)
        }
        return when (validation) {
            ScheduleValidation.Valid -> null
            is ScheduleValidation.Invalid -> ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.INVALID_SCHEDULE,
                message = validation.errors.joinToString(", ") { it.userMessage },
                validationErrors = validation.errors,
            )
        }
    }

    private suspend fun profileFailure(schedule: RecordingSchedule): ScheduleWorkflowResult? =
        captureScheduleFailure {
            val profile = profileRepository.get(schedule.profileId)
            if (profile == null) {
                ScheduleWorkflowResult.Rejected(
                    code = ScheduleWorkflowFailureCode.PROFILE_NOT_FOUND,
                    message = "The selected Pixel Camera profile is not installed.",
                )
            } else {
                profile.readinessFailure(schedule.capture)
                    ?: profile.rehearsalFailure(schedule.capture)
                    ?: profile.runtimeFailure(schedule.enabled)
            }
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                ScheduleWorkflowResult.Failed(
                    code = ScheduleWorkflowFailureCode.PREFLIGHT_FAILED,
                    message = "Current schedule readiness could not be verified: ${failure.safeMessage()}.",
                )
            },
        )

    private suspend fun PixelCameraProfile.rehearsalFailure(
        capture: CaptureConfiguration,
    ): ScheduleWorkflowResult.Rejected? =
        if (executionRepository.hasVerifiedRehearsal(this, capture)) {
            null
        } else {
            ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.PROFILE_NOT_VERIFIED,
                message = "The selected capture configuration has not passed a production rehearsal.",
            )
        }

    private suspend fun PixelCameraProfile.runtimeFailure(
        requireRuntimeReady: Boolean,
    ): ScheduleWorkflowResult.Rejected? {
        if (!requireRuntimeReady) return null
        val preflight = preflightProbe.inspect(listOf(this))
        return if (preflight.hasAllRequiredChecksPassed()) {
            null
        } else {
            ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.RUNTIME_NOT_READY,
                message = preflight.requiredFailureMessage(),
            )
        }
    }
}

private suspend fun ExecutionRepository.hasVerifiedRehearsal(
    profile: PixelCameraProfile,
    capture: CaptureConfiguration,
): Boolean = latestSuccessfulRehearsal(profile.id, capture)
    ?.qualifiesRehearsal(profile, capture) == true

private fun PixelCameraProfile.readinessFailure(
    capture: CaptureConfiguration,
): ScheduleWorkflowResult.Rejected? = when {
    !supports(capture) -> ScheduleWorkflowResult.Rejected(
        code = ScheduleWorkflowFailureCode.CAPTURE_NOT_SUPPORTED,
        message = "The selected profile has no verified selectors for $capture.",
    )
    compatibility != ProfileCompatibility.VERIFIED || verifiedAt == null ->
        ScheduleWorkflowResult.Rejected(
            code = ScheduleWorkflowFailureCode.PROFILE_NOT_VERIFIED,
            message = "The selected Pixel Camera profile has not passed a production rehearsal.",
        )
    else -> null
}

private fun PreflightReport.hasAllRequiredChecksPassed(): Boolean =
    requiredPreflightChecks.all { required ->
        checks.singleOrNull { it.type == required }?.let { check ->
            check.severity != PreflightSeverity.BLOCKING || check.status == PreflightStatus.PASSED
        } == true
    }

private fun PreflightReport.requiredFailureMessage(): String =
    requiredPreflightChecks.mapNotNull { required ->
        val check = checks.singleOrNull { it.type == required }
        if (
            check != null &&
            (check.severity != PreflightSeverity.BLOCKING || check.status == PreflightStatus.PASSED)
        ) {
            null
        } else {
            check?.message ?: "$required was not reported."
        }
    }.joinToString(" ")

private val ScheduleValidationError.userMessage: String
    get() = when (this) {
        ScheduleValidationError.BLANK_NAME -> "Name is required"
        ScheduleValidationError.STOP_NOT_AFTER_START -> "Stop time must be after start time"
        ScheduleValidationError.UPDATED_BEFORE_CREATED -> "Schedule revision is invalid"
        ScheduleValidationError.START_NOT_IN_FUTURE -> "Start time must be in the future"
        ScheduleValidationError.SCHEDULE_DISABLED -> "Schedule is disabled"
    }

private val requiredPreflightChecks = setOf(
    PreflightCheckType.EXACT_ALARMS,
    PreflightCheckType.NOTIFICATIONS,
    PreflightCheckType.MEDIA_VIDEO_ACCESS,
    PreflightCheckType.FULL_SCREEN_INTENT,
    PreflightCheckType.PIXEL_CAMERA_INSTALLED,
    PreflightCheckType.SECURE_CAMERA_RESOLVES,
    PreflightCheckType.DEVICE_WAKE,
    PreflightCheckType.ACCESSIBILITY_ENABLED,
    PreflightCheckType.ACCESSIBILITY_CONNECTED,
    PreflightCheckType.PROFILE_AVAILABLE,
    PreflightCheckType.PROFILE_COMPATIBILITY,
    PreflightCheckType.REHEARSAL_CURRENT,
    PreflightCheckType.BATTERY,
    PreflightCheckType.CHARGING,
    PreflightCheckType.STORAGE,
)
