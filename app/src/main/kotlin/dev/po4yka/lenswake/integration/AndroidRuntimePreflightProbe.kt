package dev.po4yka.lenswake.integration

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityService
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.platform.PlatformCapability
import dev.po4yka.lenswake.platform.SecurePixelCameraResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** Android-backed readiness inspection. It is observational and never grants special access. */
class AndroidRuntimePreflightProbe(
    context: Context,
    private val cameraEnvironmentProbe: AndroidPixelCameraEnvironmentProbe,
    private val secureCameraResolver: SecurePixelCameraResolver = SecurePixelCameraResolver(context),
) : RuntimePreflightProbe {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val accessibilityManager =
        applicationContext.getSystemService(AccessibilityManager::class.java)

    override val invalidations: Flow<Unit> = PixelCameraAccessibilityRuntime.connectionState
        .drop(1)
        .map { }

    override fun inspect(profiles: List<PixelCameraProfile>): PreflightReport {
        val environmentInspection = runCatching(cameraEnvironmentProbe::inspect)
        val environmentResult = environmentInspection.getOrNull()
        val currentEnvironment = (environmentResult as? PortResult.Observed)?.value
        val cameraFailure = (environmentResult as? PortResult.Unavailable)?.failure
        val cameraStatus = when {
            currentEnvironment != null -> PreflightStatus.PASSED
            environmentInspection.isFailure -> PreflightStatus.UNKNOWN
            else -> PreflightStatus.FAILED
        }

        return PreflightReport(
            checks = listOf(
                exactAlarmCheck(),
                PreflightCheck(
                    type = PreflightCheckType.PIXEL_CAMERA_INSTALLED,
                    severity = PreflightSeverity.BLOCKING,
                    status = cameraStatus,
                    message = currentEnvironment?.let {
                        "Pixel Camera ${it.cameraVersionCode} is installed on ${it.deviceModel}."
                    } ?: cameraFailure?.message ?: environmentInspection.exceptionOrNull()?.let {
                        "Pixel Camera availability could not be checked: ${it.javaClass.simpleName}."
                    } ?: "Pixel Camera availability could not be determined.",
                ),
                secureCameraCheck(),
                accessibilityEnabledCheck(),
                accessibilityConnectedCheck(),
                profileAvailableCheck(profiles),
                profileCompatibilityCheck(profiles, currentEnvironment),
                PreflightCheck(
                    type = PreflightCheckType.REHEARSAL_CURRENT,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.UNKNOWN,
                    message = "No successful rehearsal is recorded for the current environment.",
                ),
                PreflightCheck(
                    type = PreflightCheckType.PRIVILEGED_FALLBACK,
                    severity = PreflightSeverity.WARNING,
                    status = PreflightStatus.UNKNOWN,
                    message = "Optional privileged fallback has not been configured or verified.",
                ),
            ),
        )
    }

    private fun exactAlarmCheck(): PreflightCheck = runCatching {
        val available = alarmManager.canScheduleExactAlarms()
        PreflightCheck(
            type = PreflightCheckType.EXACT_ALARMS,
            severity = PreflightSeverity.BLOCKING,
            status = if (available) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (available) {
                "Android currently allows Lenswake to schedule exact alarms."
            } else {
                "Exact-alarm access is not granted in system settings."
            },
        )
    }.getOrElse { error ->
        PreflightCheck(
            type = PreflightCheckType.EXACT_ALARMS,
            severity = PreflightSeverity.BLOCKING,
            status = PreflightStatus.UNKNOWN,
            message = "Exact-alarm access could not be checked: ${error.javaClass.simpleName}.",
        )
    }

    private fun secureCameraCheck(): PreflightCheck = runCatching(secureCameraResolver::resolve)
        .fold(
            onSuccess = { result ->
                when (result) {
                    is PlatformCapability.Available -> PreflightCheck(
                        type = PreflightCheckType.SECURE_CAMERA_RESOLVES,
                        severity = PreflightSeverity.BLOCKING,
                        status = PreflightStatus.PASSED,
                        message = "Secure camera resolves to ${result.value.component.flattenToShortString()}.",
                    )

                    is PlatformCapability.Unavailable -> PreflightCheck(
                        type = PreflightCheckType.SECURE_CAMERA_RESOLVES,
                        severity = PreflightSeverity.BLOCKING,
                        status = PreflightStatus.FAILED,
                        message = result.detail,
                    )
                }
            },
            onFailure = { error ->
                PreflightCheck(
                    type = PreflightCheckType.SECURE_CAMERA_RESOLVES,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.UNKNOWN,
                    message = "Secure camera resolution could not be checked: ${error.javaClass.simpleName}.",
                )
            },
        )

    private fun accessibilityEnabledCheck(): PreflightCheck {
        val expectedComponent = ComponentName(
            applicationContext,
            PixelCameraAccessibilityService::class.java,
        )
        val enabled = runCatching {
            accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { service ->
                    val info = service.resolveInfo.serviceInfo
                    ComponentName(info.packageName, info.name) == expectedComponent
                }
        }.getOrElse { error ->
            return PreflightCheck(
                type = PreflightCheckType.ACCESSIBILITY_ENABLED,
                severity = PreflightSeverity.BLOCKING,
                status = PreflightStatus.UNKNOWN,
                message = "Accessibility status could not be checked: ${error.javaClass.simpleName}.",
            )
        }
        return PreflightCheck(
            type = PreflightCheckType.ACCESSIBILITY_ENABLED,
            severity = PreflightSeverity.BLOCKING,
            status = if (enabled) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (enabled) {
                "Lenswake Accessibility Service is enabled in system settings."
            } else {
                "Lenswake Accessibility Service is not enabled in system settings."
            },
        )
    }

    private fun accessibilityConnectedCheck(): PreflightCheck {
        val connected = PixelCameraAccessibilityRuntime.isConnected
        return PreflightCheck(
            type = PreflightCheckType.ACCESSIBILITY_CONNECTED,
            severity = PreflightSeverity.BLOCKING,
            status = if (connected) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (connected) {
                "Lenswake Accessibility Service is connected to this process."
            } else {
                "Lenswake Accessibility Service is not connected to this process."
            },
        )
    }

    private fun profileAvailableCheck(profiles: List<PixelCameraProfile>): PreflightCheck {
        return PreflightCheck(
            type = PreflightCheckType.PROFILE_AVAILABLE,
            severity = PreflightSeverity.BLOCKING,
            status = if (profiles.isNotEmpty()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (profiles.isEmpty()) {
                "No Pixel Camera profile is persisted."
            } else {
                "${profiles.size} Pixel Camera profile(s) are persisted."
            },
        )
    }

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
        val compatibilities = profiles
            .filter { it.targetsCurrentDeviceFamily(currentEnvironment) }
            .map { it.compatibilityFor(currentEnvironment) }
        val best = compatibilities.minByOrNull(ProfileCompatibility::ordinal)
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

    private fun PixelCameraProfile.targetsCurrentDeviceFamily(
        current: PixelCameraEnvironment,
    ): Boolean =
        environment.deviceManufacturer == current.deviceManufacturer &&
            environment.deviceModel == current.deviceModel &&
            environment.cameraPackage == current.cameraPackage
}
