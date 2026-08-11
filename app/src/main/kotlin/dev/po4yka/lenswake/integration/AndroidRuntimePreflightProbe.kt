package dev.po4yka.lenswake.integration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.storage.StorageManager
import android.provider.Settings
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityService
import dev.po4yka.lenswake.application.RuntimeCapabilityObservation
import dev.po4yka.lenswake.application.RuntimePreflightEvaluator
import dev.po4yka.lenswake.application.RuntimePreflightObservation
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.localizedText
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.platform.PlatformCapability
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.platform.DeviceWakeController
import dev.po4yka.lenswake.platform.SecurePixelCameraResolver
import dev.po4yka.lenswake.ui.AndroidUiStringProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CancellationException

/** Android-backed readiness inspection. It is observational and never grants special access. */
class AndroidRuntimePreflightProbe(
    context: Context,
    private val cameraEnvironmentProbe: AndroidPixelCameraEnvironmentProbe,
    private val executionRepository: ExecutionRepository,
    private val deviceWakeController: DeviceWakeController = AndroidDeviceWakeController(context),
    private val secureCameraResolver: SecurePixelCameraResolver = SecurePixelCameraResolver(context),
    private val evaluator: RuntimePreflightEvaluator = RuntimePreflightEvaluator(
        AndroidUiStringProvider(context),
    ),
) : RuntimePreflightProbe {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)
    private val storageManager = applicationContext.getSystemService(StorageManager::class.java)

    override val invalidations: Flow<Unit> = PixelCameraAccessibilityRuntime.connectionState
        .map { }

    override suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport {
        val camera = observeCamera(profiles)

        return evaluator.evaluate(
            observation = RuntimePreflightObservation(
                exactAlarms = exactAlarmObservation(),
                notifications = notificationObservation(),
                mediaVideoAccess = mediaVideoAccessObservation(),
                fullScreenIntent = fullScreenIntentObservation(),
                pixelCameraInstalled = camera.capability,
                cameraEnvironment = camera.environment,
                secureCameraResolves = secureCameraObservation(),
                deviceWake = deviceWakeObservation(),
                accessibilityEnabled = accessibilityEnabledObservation(),
                accessibilityConnected = accessibilityConnectedObservation(),
                battery = batteryObservation(
                    runCatching {
                        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    }.getOrNull(),
                ),
                charging = chargingObservation(
                    runCatching {
                        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    }.getOrNull(),
                ),
                storage = storageObservation(),
                successfulRehearsals = camera.rehearsals.getOrDefault(emptyMap()),
                rehearsalEvidenceFailure = camera.rehearsals.exceptionOrNull()?.let {
                    localizedText(R.string.preflight_rehearsal_evidence_load_failed)
                },
            ),
            profiles = profiles,
        )
    }

    private suspend fun observeCamera(profiles: List<PixelCameraProfile>): CameraObservation {
        val inspection = runSuspendCatchingPreservingCancellation(cameraEnvironmentProbe::inspect)
        val result = inspection.getOrNull()
        val environment = (result as? PortResult.Observed)?.value
        val cameraFailure = (result as? PortResult.Unavailable)?.failure
        val status = when {
            environment != null -> PreflightStatus.PASSED
            inspection.isFailure -> PreflightStatus.UNKNOWN
            else -> PreflightStatus.FAILED
        }
        val rehearsals = runSuspendCatchingPreservingCancellation {
            profiles
                .filter { profile -> profile.environment == environment }
                .mapNotNull { profile ->
                    executionRepository.latestSuccessfulRehearsal(profile.id)
                        ?.let { profile.id to it }
                }
                .toMap()
                .takeIf { environment != null }
                .orEmpty()
        }
        val message = environment?.let {
            localizedText(
                R.string.preflight_pixel_camera_installed,
                it.cameraVersionCode,
                it.deviceModel,
            )
        } ?: if (cameraFailure != null || inspection.isFailure) {
            localizedText(R.string.preflight_pixel_camera_check_failed)
        } else {
            localizedText(R.string.preflight_pixel_camera_unknown)
        }
        return CameraObservation(
            capability = RuntimeCapabilityObservation(status, message),
            environment = environment,
            rehearsals = rehearsals,
        )
    }

    private fun exactAlarmObservation(): RuntimeCapabilityObservation = runCatching {
        val available = alarmManager.canScheduleExactAlarms()
        RuntimeCapabilityObservation(
            status = if (available) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = localizedText(
                if (available) R.string.preflight_exact_alarms_available
                else R.string.preflight_exact_alarms_unavailable,
            ),
            remediation = if (available) null else SetupRemediationAction.OPEN_EXACT_ALARM_SETTINGS,
        )
    }.getOrElse {
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_exact_alarms_check_failed),
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
                message = localizedText(R.string.preflight_notifications_permission_missing),
                remediation = SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
            )

            !notificationsEnabled -> RuntimeCapabilityObservation(
                status = PreflightStatus.FAILED,
                message = localizedText(R.string.preflight_notifications_disabled),
                remediation = SetupRemediationAction.OPEN_NOTIFICATION_SETTINGS,
            )

            else -> RuntimeCapabilityObservation(
                status = PreflightStatus.PASSED,
                message = localizedText(R.string.preflight_notifications_available),
            )
        }
    }.getOrElse {
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_notifications_check_failed),
        )
    }

    private fun mediaVideoAccessObservation(): RuntimeCapabilityObservation = runCatching {
        val fullAccess = applicationContext.checkSelfPermission(
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED
        val partialAccess = applicationContext.checkSelfPermission(
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
        RuntimeCapabilityObservation(
            status = if (fullAccess) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = localizedText(
                when {
                    fullAccess -> R.string.preflight_media_video_access_available
                    partialAccess -> R.string.preflight_media_video_access_partial
                    else -> R.string.preflight_media_video_access_unavailable
                },
            ),
            remediation = if (fullAccess) null else SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION,
        )
    }.getOrElse {
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_media_video_access_check_failed),
        )
    }

    private fun fullScreenIntentObservation(): RuntimeCapabilityObservation = runCatching {
        val available = notificationManager.canUseFullScreenIntent()
        RuntimeCapabilityObservation(
            status = if (available) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = localizedText(
                if (available) R.string.preflight_full_screen_intent_available
                else R.string.preflight_full_screen_intent_unavailable,
            ),
            remediation = if (available) null else SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS,
        )
    }.getOrElse {
        RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_full_screen_intent_check_failed),
        )
    }

    private fun secureCameraObservation(): RuntimeCapabilityObservation =
        runCatching(secureCameraResolver::resolve)
            .fold(
                onSuccess = { result ->
                    when (result) {
                        is PlatformCapability.Available -> RuntimeCapabilityObservation(
                            status = PreflightStatus.PASSED,
                            message = localizedText(
                                R.string.preflight_secure_camera_available,
                                result.value.component.flattenToShortString(),
                            ),
                        )

                        is PlatformCapability.Unavailable -> RuntimeCapabilityObservation(
                            status = PreflightStatus.FAILED,
                            message = localizedText(R.string.preflight_secure_camera_unavailable),
                        )
                    }
                },
                onFailure = {
                    RuntimeCapabilityObservation(
                        status = PreflightStatus.UNKNOWN,
                        message = localizedText(R.string.preflight_secure_camera_check_failed),
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
                            message = localizedText(R.string.preflight_device_wake_available),
                        )

                        is PlatformCapability.Unavailable -> RuntimeCapabilityObservation(
                            status = PreflightStatus.FAILED,
                            message = localizedText(R.string.preflight_device_wake_unavailable),
                        )
                    }
                },
                onFailure = {
                    RuntimeCapabilityObservation(
                        status = PreflightStatus.UNKNOWN,
                        message = localizedText(R.string.preflight_device_wake_check_failed),
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
        }.getOrElse {
            return RuntimeCapabilityObservation(
                status = PreflightStatus.UNKNOWN,
                message = localizedText(R.string.preflight_accessibility_check_failed),
            )
        }
        return RuntimeCapabilityObservation(
            status = if (enabled) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = localizedText(
                if (enabled) R.string.preflight_accessibility_enabled
                else R.string.preflight_accessibility_disabled,
            ),
            remediation = if (enabled) null else SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
        )
    }

    private fun accessibilityConnectedObservation(): RuntimeCapabilityObservation {
        val connected = PixelCameraAccessibilityRuntime.isConnected
        return RuntimeCapabilityObservation(
            status = if (connected) PreflightStatus.PASSED else PreflightStatus.FAILED,
            message = localizedText(
                if (connected) R.string.preflight_accessibility_connected
                else R.string.preflight_accessibility_disconnected,
            ),
            remediation = if (connected) null else SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS,
        )
    }

    private fun storageObservation(): RuntimeCapabilityObservation = runCatching {
        storageObservation(
            availableBytes = storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT),
        )
    }.getOrElse {
        storageObservation(
            availableBytes = null,
        )
    }
}

internal const val MINIMUM_BATTERY_PERCENT = 30
internal const val MINIMUM_AVAILABLE_STORAGE_BYTES = 1024L * 1024L * 1024L

internal fun batteryObservation(percent: Int?): RuntimeCapabilityObservation {
    val validPercent = percent?.takeIf { it in 0..100 }
        ?: return RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_battery_unknown),
        )
    return RuntimeCapabilityObservation(
        status = if (validPercent >= MINIMUM_BATTERY_PERCENT) {
            PreflightStatus.PASSED
        } else {
            PreflightStatus.FAILED
        },
        message = localizedText(
            if (validPercent >= MINIMUM_BATTERY_PERCENT) R.string.preflight_battery_sufficient
            else R.string.preflight_battery_low,
            validPercent,
            MINIMUM_BATTERY_PERCENT,
        ),
    )
}

internal fun chargingObservation(status: Int?): RuntimeCapabilityObservation = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING,
    BatteryManager.BATTERY_STATUS_FULL,
    -> RuntimeCapabilityObservation(
        status = PreflightStatus.PASSED,
        message = localizedText(R.string.preflight_charging),
    )

    BatteryManager.BATTERY_STATUS_DISCHARGING,
    BatteryManager.BATTERY_STATUS_NOT_CHARGING,
    -> RuntimeCapabilityObservation(
        status = PreflightStatus.FAILED,
        message = localizedText(R.string.preflight_not_charging),
    )

    else -> RuntimeCapabilityObservation(
        status = PreflightStatus.UNKNOWN,
        message = localizedText(R.string.preflight_charging_unknown),
    )
}

internal fun storageObservation(
    availableBytes: Long?,
): RuntimeCapabilityObservation {
    if (availableBytes == null || availableBytes < 0L) {
        return RuntimeCapabilityObservation(
            status = PreflightStatus.UNKNOWN,
            message = localizedText(R.string.preflight_storage_unknown),
        )
    }
    val sufficient = availableBytes >= MINIMUM_AVAILABLE_STORAGE_BYTES
    return RuntimeCapabilityObservation(
        status = if (sufficient) PreflightStatus.PASSED else PreflightStatus.FAILED,
        message = localizedText(
            if (sufficient) R.string.preflight_storage_sufficient else R.string.preflight_storage_low,
            availableBytes.toReadableMiB(),
            MINIMUM_AVAILABLE_STORAGE_BYTES.toReadableMiB(),
        ),
    )
}

private fun Long.toReadableMiB(): Long = this / BYTES_PER_MEBIBYTE

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

private data class CameraObservation(
    val capability: RuntimeCapabilityObservation,
    val environment: PixelCameraEnvironment?,
    val rehearsals: Result<Map<ProfileId, ExecutionSession>>,
)

internal suspend inline fun <T> runSuspendCatchingPreservingCancellation(
    crossinline operation: suspend () -> T,
): Result<T> {
    val result = runCatching { operation() }
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
    if (failure != null && failure !is Exception) throw failure
    return result
}
