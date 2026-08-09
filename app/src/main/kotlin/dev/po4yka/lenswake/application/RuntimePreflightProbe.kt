package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Reads current device capabilities without mutating system settings or persisted configuration. */
fun interface RuntimePreflightProbe {
    suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport

    val invalidations: Flow<Unit>
        get() = emptyFlow()
}

data class RuntimeCapabilityObservation(
    val status: PreflightStatus,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Runtime capability message must not be blank" }
    }
}

data class RuntimePreflightObservation(
    val exactAlarms: RuntimeCapabilityObservation,
    val pixelCameraInstalled: RuntimeCapabilityObservation,
    val cameraEnvironment: PixelCameraEnvironment?,
    val secureCameraResolves: RuntimeCapabilityObservation,
    val deviceWake: RuntimeCapabilityObservation,
    val accessibilityEnabled: RuntimeCapabilityObservation,
    val accessibilityConnected: RuntimeCapabilityObservation,
    val successfulRehearsals: Map<ProfileId, ExecutionSession> = emptyMap(),
    val rehearsalEvidenceFailure: String? = null,
)

/** Pure policy that converts observed platform facts and persisted profiles into readiness. */
class RuntimePreflightEvaluator {
    fun evaluate(
        observation: RuntimePreflightObservation,
        profiles: List<PixelCameraProfile>,
    ): PreflightReport = PreflightReport(
        checks = listOf(
            observation.exactAlarms.toCheck(PreflightCheckType.EXACT_ALARMS),
            observation.pixelCameraInstalled.toCheck(PreflightCheckType.PIXEL_CAMERA_INSTALLED),
            observation.secureCameraResolves.toCheck(PreflightCheckType.SECURE_CAMERA_RESOLVES),
            observation.deviceWake.toCheck(PreflightCheckType.DEVICE_WAKE),
            observation.accessibilityEnabled.toCheck(PreflightCheckType.ACCESSIBILITY_ENABLED),
            observation.accessibilityConnected.toCheck(PreflightCheckType.ACCESSIBILITY_CONNECTED),
            profileAvailableCheck(profiles),
            profileCompatibilityCheck(profiles, observation.cameraEnvironment),
            rehearsalCurrentCheck(observation, profiles),
            PreflightCheck(
                type = PreflightCheckType.PRIVILEGED_FALLBACK,
                severity = PreflightSeverity.WARNING,
                status = PreflightStatus.UNKNOWN,
                message = "Optional privileged fallback has not been configured or verified.",
            ),
        ),
    )

    private fun RuntimeCapabilityObservation.toCheck(type: PreflightCheckType): PreflightCheck =
        PreflightCheck(
            type = type,
            severity = PreflightSeverity.BLOCKING,
            status = status,
            message = message,
        )

    private fun profileAvailableCheck(profiles: List<PixelCameraProfile>): PreflightCheck =
        PreflightCheck(
            type = PreflightCheckType.PROFILE_AVAILABLE,
            severity = PreflightSeverity.BLOCKING,
            status = if (profiles.isNotEmpty()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (profiles.isEmpty()) {
                "No Pixel Camera profile is persisted."
            } else {
                "${profiles.size} Pixel Camera profile(s) are persisted."
            },
        )

    private fun profileCompatibilityCheck(
        profiles: List<PixelCameraProfile>,
        currentEnvironment: PixelCameraEnvironment?,
    ): PreflightCheck {
        if (currentEnvironment == null) {
            return PreflightCheck(
                type = PreflightCheckType.PROFILE_COMPATIBILITY,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.UNKNOWN,
                message = "Profile compatibility cannot be checked without the current camera environment.",
            )
        }
        val best = profiles
            .filter { it.targetsCurrentDeviceFamily(currentEnvironment) }
            .map { it.compatibilityFor(currentEnvironment) }
            .minByOrNull(ProfileCompatibility::ordinal)
        return PreflightCheck(
            type = PreflightCheckType.PROFILE_COMPATIBILITY,
            severity = PreflightSeverity.BLOCKING,
            status = if (best == ProfileCompatibility.VERIFIED) {
                PreflightStatus.PASSED
            } else {
                PreflightStatus.FAILED
            },
            message = when (best) {
                ProfileCompatibility.VERIFIED -> "A profile is verified for the current environment."
                ProfileCompatibility.PROBABLY_COMPATIBLE -> "The closest profile requires a current-device rehearsal."
                ProfileCompatibility.NEEDS_REHEARSAL -> "The Pixel Camera environment changed; rehearsal is required."
                ProfileCompatibility.INCOMPATIBLE -> "Available profiles are incompatible with the current environment."
                null -> "No compatible profile is available for the current environment."
            },
        )
    }

    private fun rehearsalCurrentCheck(
        observation: RuntimePreflightObservation,
        profiles: List<PixelCameraProfile>,
    ): PreflightCheck {
        val currentEnvironment = observation.cameraEnvironment
            ?: return PreflightCheck(
                type = PreflightCheckType.REHEARSAL_CURRENT,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.UNKNOWN,
                message = "Rehearsal evidence cannot be checked without the current camera environment.",
            )
        observation.rehearsalEvidenceFailure?.let { failure ->
            return PreflightCheck(
                type = PreflightCheckType.REHEARSAL_CURRENT,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.UNKNOWN,
                message = failure,
            )
        }

        val exactVerifiedProfiles = profiles.filter { profile ->
            profile.environment == currentEnvironment &&
                profile.compatibilityFor(currentEnvironment) == ProfileCompatibility.VERIFIED
        }
        val qualifying = exactVerifiedProfiles.firstNotNullOfOrNull { profile ->
            observation.successfulRehearsals[profile.id]
                ?.takeIf { session ->
                    session.kind == SessionKind.REHEARSAL &&
                        session.status == SessionStatus.COMPLETED &&
                        session.profileId == profile.id &&
                        session.recordingVerifiedAt != null &&
                        session.stopActionAt != null &&
                        session.stoppedVerifiedAt != null &&
                        profile.verifiedAt == session.stoppedVerifiedAt
                }
                ?.let { profile to it }
        }
        return PreflightCheck(
            type = PreflightCheckType.REHEARSAL_CURRENT,
            severity = PreflightSeverity.BLOCKING,
            status = if (qualifying != null) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (qualifying != null) {
                "A successful start-and-stop rehearsal verifies the current Pixel Camera profile."
            } else if (exactVerifiedProfiles.isEmpty()) {
                "No exactly matching verified profile exists for the current Pixel Camera environment."
            } else {
                "The current verified profile is not linked to its latest successful start-and-stop rehearsal."
            },
        )
    }

    private fun PixelCameraProfile.targetsCurrentDeviceFamily(
        current: PixelCameraEnvironment,
    ): Boolean =
        environment.deviceManufacturer == current.deviceManufacturer &&
            environment.deviceModel == current.deviceModel &&
            environment.cameraPackage == current.cameraPackage
}
