package dev.po4yka.lenswake.integration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
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
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.platform.PlatformCapability
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.platform.DeviceWakeController
import dev.po4yka.lenswake.platform.SecurePixelCameraResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Android-backed readiness inspection. It is observational and never grants special access. */
class AndroidRuntimePreflightProbe(
    context: Context,
    private val cameraEnvironmentProbe: AndroidPixelCameraEnvironmentProbe,
    private val executionRepository: ExecutionRepository,
    private val deviceWakeController: DeviceWakeController = AndroidDeviceWakeController(context),
    private val secureCameraResolver: SecurePixelCameraResolver = SecurePixelCameraResolver(context),
    private val evaluator: RuntimePreflightEvaluator = RuntimePreflightEvaluator(),
) : RuntimePreflightProbe {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)

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
                notifications = notificationObservation(),
                fullScreenIntent = fullScreenIntentObservation(),
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
                deviceWake = deviceWakeObservation(),
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
            remediation = if (available) null else SetupRemediationAction.OPEN_EXACT_ALARM_SETTINGS,
        )
    }.getOrElse { error ->
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = "Exact-alarm access could not be checked: ${error.javaClass.simpleName}.",
        )
    }

    private fun notificationObservation(): RuntimeCapabilityObservation = runCatching {
        val permissionGranted = applicationContext.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        when {
            !permissionGranted -> RuntimeCapabilityObservation(
                status = PreflightStatus.FAILED,
                message = "Lenswake does not have notification permission.",
                remediation = SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
            )

            !notificationsEnabled -> RuntimeCapabilityObservation(
                status = PreflightStatus.FAILED,
                message = "Notifications are disabled for Lenswake in system settings.",
                remediation = SetupRemediationAction.OPEN_NOTIFICATION_SETTINGS,
            )

            else -> RuntimeCapabilityObservation(
                status = PreflightStatus.PASSED,
                message = "Notification permission is granted and notifications are enabled for Lenswake.",
            )
        }
    }.getOrElse { error ->
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = "Notification capability could not be checked: ${error.javaClass.simpleName}.",
        )
    }

    private fun fullScreenIntentObservation(): RuntimeCapabilityObservation = runCatching {
        val available = notificationManager.canUseFullScreenIntent()
        RuntimeCapabilityObservation(
            status = if (available) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = if (available) {
                "Lenswake may use full-screen intents for alarm wake handling."
            } else {
                "Full-screen intent access is not granted in system settings."
            },
            remediation = if (available) null else SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS,
        )
    }.getOrElse { error ->
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = "Full-screen intent capability could not be checked: ${error.javaClass.simpleName}.",
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

    private fun deviceWakeObservation(): RuntimeCapabilityObservation =
        runCatching(deviceWakeController::availability)
            .fold(
                onSuccess = { result ->
                    when (result) {
                        is PlatformCapability.Available -> RuntimeCapabilityObservation(
                            status = PreflightStatus.PASSED,
                            message = "The full-screen alarm display-wake path is available.",
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
                        message = "Display-wake capability could not be checked: ${error.javaClass.simpleName}.",
                    )
                },
            )

    private fun accessibilityEnabledObservation(): RuntimeCapabilityObservation {
        val expectedComponent = ComponentName(
            applicationContext,
            PixelCameraAccessibilityService::class.java,
        )
        val enabled = runCatching {
            Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
                .split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expectedComponent }
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
            remediation = if (enabled) null else SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
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
            remediation = if (connected) null else SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
        )
    }
}
