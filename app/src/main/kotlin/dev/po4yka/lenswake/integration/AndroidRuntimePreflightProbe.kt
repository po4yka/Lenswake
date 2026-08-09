package dev.po4yka.lenswake.integration

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityService
import dev.po4yka.lenswake.application.RuntimeCapabilityObservation
import dev.po4yka.lenswake.application.RuntimePreflightEvaluator
import dev.po4yka.lenswake.application.RuntimePreflightObservation
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.platform.PlatformCapability
import dev.po4yka.lenswake.platform.SecurePixelCameraResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Android-backed readiness inspection. It is observational and never grants special access. */
class AndroidRuntimePreflightProbe(
    context: Context,
    private val cameraEnvironmentProbe: AndroidPixelCameraEnvironmentProbe,
    private val executionRepository: ExecutionRepository,
    private val secureCameraResolver: SecurePixelCameraResolver = SecurePixelCameraResolver(context),
    private val evaluator: RuntimePreflightEvaluator = RuntimePreflightEvaluator(),
) : RuntimePreflightProbe {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val accessibilityManager =
        applicationContext.getSystemService(AccessibilityManager::class.java)

    override val invalidations: Flow<Unit> = PixelCameraAccessibilityRuntime.connectionState
        .map { }

    override suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport {
        val environmentInspection = runCatching(cameraEnvironmentProbe::inspect)
        val environmentResult = environmentInspection.getOrNull()
        val currentEnvironment = (environmentResult as? PortResult.Observed)?.value
        val cameraFailure = (environmentResult as? PortResult.Unavailable)?.failure
        val cameraStatus = when {
            currentEnvironment != null -> PreflightStatus.PASSED
            environmentInspection.isFailure -> PreflightStatus.UNKNOWN
            else -> PreflightStatus.FAILED
        }
        val rehearsalEvidence = runCatching {
            if (currentEnvironment == null) {
                emptyMap()
            } else {
                profiles
                    .filter { profile -> profile.environment == currentEnvironment }
                    .mapNotNull { profile ->
                        executionRepository.latestSuccessfulRehearsal(profile.id)
                            ?.let { profile.id to it }
                    }
                    .toMap()
            }
        }

        return evaluator.evaluate(
            observation = RuntimePreflightObservation(
                exactAlarms = exactAlarmObservation(),
                pixelCameraInstalled = RuntimeCapabilityObservation(
                    status = cameraStatus,
                    message = currentEnvironment?.let {
                        "Pixel Camera ${it.cameraVersionCode} is installed on ${it.deviceModel}."
                    } ?: cameraFailure?.message ?: environmentInspection.exceptionOrNull()?.let {
                        "Pixel Camera availability could not be checked: ${it.javaClass.simpleName}."
                    } ?: "Pixel Camera availability could not be determined.",
                ),
                cameraEnvironment = currentEnvironment,
                secureCameraResolves = secureCameraObservation(),
                deviceWake = RuntimeCapabilityObservation(
                    status = PreflightStatus.FAILED,
                    message = "No verified device-wake implementation is configured; unattended locked-screen automation is blocked.",
                ),
                accessibilityEnabled = accessibilityEnabledObservation(),
                accessibilityConnected = accessibilityConnectedObservation(),
                successfulRehearsals = rehearsalEvidence.getOrDefault(emptyMap()),
                rehearsalEvidenceFailure = rehearsalEvidence.exceptionOrNull()?.let { error ->
                    "Successful rehearsal evidence could not be loaded: ${error.javaClass.simpleName}."
                },
            ),
            profiles = profiles,
        )
    }

    private fun exactAlarmObservation(): RuntimeCapabilityObservation = runCatching {
        val available = alarmManager.canScheduleExactAlarms()
        RuntimeCapabilityObservation(
            status = if (available) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (available) {
                "Android currently allows Lenswake to schedule exact alarms."
            } else {
                "Exact-alarm access is not granted in system settings."
            },
        )
    }.getOrElse { error ->
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = "Exact-alarm access could not be checked: ${error.javaClass.simpleName}.",
        )
    }

    private fun secureCameraObservation(): RuntimeCapabilityObservation =
        runCatching(secureCameraResolver::resolve)
            .fold(
                onSuccess = { result ->
                    when (result) {
                        is PlatformCapability.Available -> RuntimeCapabilityObservation(
                            status = PreflightStatus.PASSED,
                            message = "Secure camera resolves to ${result.value.component.flattenToShortString()}.",
                        )

                        is PlatformCapability.Unavailable -> RuntimeCapabilityObservation(
                            status = PreflightStatus.FAILED,
                            message = result.detail,
                        )
                    }
                },
                onFailure = { error ->
                    RuntimeCapabilityObservation(
                        status = PreflightStatus.UNKNOWN,
                        message = "Secure camera resolution could not be checked: ${error.javaClass.simpleName}.",
                    )
                },
            )

    private fun accessibilityEnabledObservation(): RuntimeCapabilityObservation {
        val expectedComponent = ComponentName(
            applicationContext,
            PixelCameraAccessibilityService::class.java,
        )
        val enabled = runCatching {
            accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { service ->
                    val info = service.resolveInfo.serviceInfo
                    ComponentName.createRelative(info.packageName, info.name) == expectedComponent
                }
        }.getOrElse { error ->
            return RuntimeCapabilityObservation(
                status = PreflightStatus.UNKNOWN,
                message = "Accessibility status could not be checked: ${error.javaClass.simpleName}.",
            )
        }
        return RuntimeCapabilityObservation(
            status = if (enabled) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (enabled) {
                "Lenswake Accessibility Service is enabled in system settings."
            } else {
                "Lenswake Accessibility Service is not enabled in system settings."
            },
        )
    }

    private fun accessibilityConnectedObservation(): RuntimeCapabilityObservation {
        val connected = PixelCameraAccessibilityRuntime.isConnected
        return RuntimeCapabilityObservation(
            status = if (connected) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (connected) {
                "Lenswake Accessibility Service is connected to this process."
            } else {
                "Lenswake Accessibility Service is not connected to this process."
            },
        )
    }
}
