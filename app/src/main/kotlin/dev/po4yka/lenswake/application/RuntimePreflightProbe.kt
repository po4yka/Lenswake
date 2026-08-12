package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RehearsalVerificationPolicy
import dev.po4yka.lenswake.core.SetupRemediationAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Reads current device capabilities without mutating system settings or persisted configuration. */
fun interface RuntimePreflightProbe {
    suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport

    suspend fun inspectForCapture(
        profiles: List<PixelCameraProfile>,
        capture: CaptureConfiguration,
    ): PreflightReport = inspect(profiles)

    val invalidations: Flow<Unit>
        get() = emptyFlow()
}

data class RuntimeCapabilityObservation(
    val status: PreflightStatus,
    val message: LocalizedText,
    val remediation: SetupRemediationAction? = null,
) {
    init {
        require(message.resourceId != 0) { "Runtime capability message resource must be valid" }
    }
}

data class RuntimePreflightObservation(
    val exactAlarms: RuntimeCapabilityObservation,
    val notifications: RuntimeCapabilityObservation,
    val mediaVideoAccess: RuntimeCapabilityObservation,
    val fullScreenIntent: RuntimeCapabilityObservation,
    val pixelCameraInstalled: RuntimeCapabilityObservation,
    val cameraEnvironment: PixelCameraEnvironment?,
    val secureCameraResolves: RuntimeCapabilityObservation,
    val deviceWake: RuntimeCapabilityObservation,
    val accessibilityEnabled: RuntimeCapabilityObservation,
    val accessibilityConnected: RuntimeCapabilityObservation,
    val battery: RuntimeCapabilityObservation,
    val charging: RuntimeCapabilityObservation,
    val storage: RuntimeCapabilityObservation,
    val successfulRehearsals: Map<ProfileId, ExecutionSession> = emptyMap(),
    val rehearsalEvidenceFailure: LocalizedText? = null,
    val requiredCapture: CaptureConfiguration? = null,
)

/** Pure policy that converts observed platform facts and persisted profiles into readiness. */
class RuntimePreflightEvaluator(
    private val strings: LocalizedTextResolver,
) {
    fun evaluate(
        observation: RuntimePreflightObservation,
        profiles: List<PixelCameraProfile>,
    ): PreflightReport = PreflightReport(
        checks = listOf(
            observation.exactAlarms.toCheck(PreflightCheckType.EXACT_ALARMS),
            observation.notifications.toCheck(PreflightCheckType.NOTIFICATIONS),
            observation.mediaVideoAccess.toCheck(PreflightCheckType.MEDIA_VIDEO_ACCESS),
            observation.fullScreenIntent.toCheck(PreflightCheckType.FULL_SCREEN_INTENT),
            observation.pixelCameraInstalled.toCheck(PreflightCheckType.PIXEL_CAMERA_INSTALLED),
            observation.secureCameraResolves.toCheck(PreflightCheckType.SECURE_CAMERA_RESOLVES),
            observation.deviceWake.toCheck(PreflightCheckType.DEVICE_WAKE),
            observation.accessibilityEnabled.toCheck(PreflightCheckType.ACCESSIBILITY_ENABLED),
            observation.accessibilityConnected.toCheck(PreflightCheckType.ACCESSIBILITY_CONNECTED),
            observation.battery.toCheck(PreflightCheckType.BATTERY),
            observation.charging.toResourceCheck(
                type = PreflightCheckType.CHARGING,
                knownFailureSeverity = PreflightSeverity.WARNING,
            ),
            observation.storage.toResourceCheck(
                type = PreflightCheckType.STORAGE,
                knownFailureSeverity = PreflightSeverity.WARNING,
            ),
            profileAvailableCheck(profiles),
            profileCompatibilityCheck(profiles, observation.cameraEnvironment),
            rehearsalCurrentCheck(observation, profiles),
            PreflightCheck(
                type = PreflightCheckType.PRIVILEGED_FALLBACK,
                severity = PreflightSeverity.WARNING,
                status = PreflightStatus.UNKNOWN,
                message = strings.get(R.string.preflight_privileged_fallback_unchecked),
            ),
        ),
    )

    private fun RuntimeCapabilityObservation.toCheck(type: PreflightCheckType): PreflightCheck =
        PreflightCheck(
            type = type,
            severity = PreflightSeverity.BLOCKING,
            status = status,
            message = message.resolve(strings),
            remediation = remediation,
        )

    /** Unknown resource state blocks scheduling; known advisory failures remain visible warnings. */
    private fun RuntimeCapabilityObservation.toResourceCheck(
        type: PreflightCheckType,
        knownFailureSeverity: PreflightSeverity,
    ): PreflightCheck = PreflightCheck(
        type = type,
        severity = if (status == PreflightStatus.UNKNOWN) {
            PreflightSeverity.BLOCKING
        } else {
            knownFailureSeverity
        },
        status = status,
        message = message.resolve(strings),
        remediation = remediation,
    )

    private fun profileAvailableCheck(profiles: List<PixelCameraProfile>): PreflightCheck =
        PreflightCheck(
            type = PreflightCheckType.PROFILE_AVAILABLE,
            severity = PreflightSeverity.BLOCKING,
            status = if (profiles.isNotEmpty()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (profiles.isEmpty()) {
                strings.get(R.string.preflight_profile_none)
            } else {
                strings.quantity(
                    R.plurals.preflight_profile_count,
                    profiles.size,
                    profiles.size,
                )
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
                message = strings.get(R.string.preflight_profile_environment_unknown),
            )
        }
        val best = profiles
            .filter { it.isSupportedRuntimeProfile() }
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
                ProfileCompatibility.VERIFIED -> strings.get(R.string.preflight_profile_verified)
                ProfileCompatibility.PROBABLY_COMPATIBLE -> strings.get(
                    R.string.preflight_profile_probably_compatible,
                )
                ProfileCompatibility.NEEDS_REHEARSAL -> strings.get(
                    R.string.preflight_profile_needs_rehearsal,
                )
                ProfileCompatibility.INCOMPATIBLE -> strings.get(R.string.preflight_profile_incompatible)
                null -> strings.get(R.string.preflight_profile_unavailable)
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
                message = strings.get(R.string.preflight_rehearsal_environment_unknown),
            )
        observation.rehearsalEvidenceFailure?.let { failure ->
            return PreflightCheck(
                type = PreflightCheckType.REHEARSAL_CURRENT,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.UNKNOWN,
                message = failure.resolve(strings),
            )
        }

        val exactVerifiedProfiles = profiles.filter { profile ->
            profile.isSupportedRuntimeProfile() &&
            profile.environment == currentEnvironment &&
                profile.compatibilityFor(currentEnvironment) == ProfileCompatibility.VERIFIED
        }
        val qualifying = exactVerifiedProfiles.firstNotNullOfOrNull { profile ->
            observation.successfulRehearsals[profile.id]
                ?.takeIf { session ->
                    (observation.requiredCapture == null || session.capture == observation.requiredCapture) &&
                        RehearsalVerificationPolicy.qualifies(
                            session,
                            profile,
                            observation.requiredCapture ?: session.capture,
                        )
                }
                ?.let { profile to it }
        }
        return PreflightCheck(
            type = PreflightCheckType.REHEARSAL_CURRENT,
            severity = PreflightSeverity.BLOCKING,
            status = if (qualifying != null) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (qualifying != null) {
                strings.get(R.string.preflight_rehearsal_verified)
            } else if (exactVerifiedProfiles.isEmpty()) {
                strings.get(R.string.preflight_rehearsal_profile_missing)
            } else {
                strings.get(R.string.preflight_rehearsal_unlinked)
            },
        )
    }

    private fun PixelCameraProfile.targetsCurrentDeviceFamily(
        current: PixelCameraEnvironment,
    ): Boolean =
        environment.deviceManufacturer == current.deviceManufacturer &&
            environment.deviceModel == current.deviceModel &&
            environment.cameraPackage == current.cameraPackage

    private fun PixelCameraProfile.isSupportedRuntimeProfile(): Boolean =
        isSupportedPixelCameraRuntime(environment)
}
