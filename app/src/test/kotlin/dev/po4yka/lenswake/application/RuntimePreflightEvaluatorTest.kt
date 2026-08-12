package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleReadiness
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.definitionFingerprint
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY
import dev.po4yka.lenswake.ui.TestUiStringProvider
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RuntimePreflightEvaluatorTest {
    private val evaluator = RuntimePreflightEvaluator(TestUiStringProvider)

    @Test
    fun preservesPlatformFailureAndUnknownStatesFailClosed() {
        val report = evaluator.evaluate(
            observation = observation(
                exactAlarms = failed("Exact alarms denied."),
                pixelCameraInstalled = unknown("Camera probe failed."),
                cameraEnvironment = null,
                secureCameraResolves = unknown("Resolver failed."),
                accessibilityEnabled = passed("Accessibility enabled."),
                accessibilityConnected = failed("Accessibility disconnected."),
            ),
            profiles = emptyList(),
        )
        val checks = report.checks.associateBy { it.type }

        assertEquals(PreflightStatus.FAILED, checks.getValue(PreflightCheckType.EXACT_ALARMS).status)
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.NOTIFICATIONS).status)
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.MEDIA_VIDEO_ACCESS).status)
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.FULL_SCREEN_INTENT).status)
        assertEquals(PreflightStatus.UNKNOWN, checks.getValue(PreflightCheckType.PIXEL_CAMERA_INSTALLED).status)
        assertEquals(PreflightStatus.UNKNOWN, checks.getValue(PreflightCheckType.SECURE_CAMERA_RESOLVES).status)
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.ACCESSIBILITY_ENABLED).status)
        assertEquals(PreflightStatus.FAILED, checks.getValue(PreflightCheckType.ACCESSIBILITY_CONNECTED).status)
        assertEquals(PreflightStatus.FAILED, checks.getValue(PreflightCheckType.PROFILE_AVAILABLE).status)
        assertEquals(PreflightStatus.UNKNOWN, checks.getValue(PreflightCheckType.PROFILE_COMPATIBILITY).status)
        assertInstanceOf(ScheduleReadiness.Blocked::class.java, report.readiness)
    }

    @Test
    fun verifiedProfilePassesCompatibilityButRehearsalStillBlocksScheduling() {
        val environment = environment()
        val report = evaluator.evaluate(
            observation = observation(cameraEnvironment = environment),
            profiles = listOf(profile(environment)),
        )
        val checks = report.checks.associateBy { it.type }

        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.PROFILE_AVAILABLE).status)
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.PROFILE_COMPATIBILITY).status)
        val blocked = assertInstanceOf(ScheduleReadiness.Blocked::class.java, report.readiness)
        assertEquals(listOf(PreflightCheckType.REHEARSAL_CURRENT), blocked.blockers.map { it.type })
    }

    @Test
    fun notificationAndFullScreenFailuresAreSeparateBlockingChecksWithTypedRemediation() {
        val report = evaluator.evaluate(
            observation = observation(
                notifications = RuntimeCapabilityObservation(
                    PreflightStatus.FAILED,
                    localizedText(R.string.status_failed),
                    SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
                ),
                fullScreenIntent = RuntimeCapabilityObservation(
                    PreflightStatus.FAILED,
                    localizedText(R.string.status_failed),
                    SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS,
                ),
            ),
            profiles = emptyList(),
        )
        val checks = report.checks.associateBy { it.type }

        assertEquals(
            SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
            checks.getValue(PreflightCheckType.NOTIFICATIONS).remediation,
        )
        assertEquals(
            SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS,
            checks.getValue(PreflightCheckType.FULL_SCREEN_INTENT).remediation,
        )
    }

    @Test
    fun missingSavedVideoAccessIsBlockingAndRequestsTheRuntimePermission() {
        val report = evaluator.evaluate(
            observation = observation(
                mediaVideoAccess = RuntimeCapabilityObservation(
                    PreflightStatus.FAILED,
                    localizedText(R.string.status_failed),
                    SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION,
                ),
            ),
            profiles = emptyList(),
        )

        val check = report.checks.associateBy { it.type }.getValue(PreflightCheckType.MEDIA_VIDEO_ACCESS)
        assertEquals(PreflightSeverity.BLOCKING, check.severity)
        assertEquals(PreflightStatus.FAILED, check.status)
        assertEquals(SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION, check.remediation)
    }

    @Test
    fun unknownResourceChecksFailClosed() {
        val report = evaluator.evaluate(
            observation = observation(
                battery = unknown("Battery unavailable."),
                charging = unknown("Charging unavailable."),
                storage = unknown("Storage unavailable."),
            ),
            profiles = emptyList(),
        )
        val checks = report.checks.associateBy { it.type }

        assertEquals(PreflightStatus.UNKNOWN, checks.getValue(PreflightCheckType.BATTERY).status)
        assertEquals(
            PreflightSeverity.BLOCKING,
            checks.getValue(PreflightCheckType.BATTERY).severity,
        )
        assertEquals(
            PreflightSeverity.BLOCKING,
            checks.getValue(PreflightCheckType.CHARGING).severity,
        )
        assertEquals(
            PreflightSeverity.BLOCKING,
            checks.getValue(PreflightCheckType.STORAGE).severity,
        )
        val blocked = assertInstanceOf(ScheduleReadiness.Blocked::class.java, report.readiness)
        assertEquals(
            setOf(PreflightCheckType.BATTERY, PreflightCheckType.CHARGING, PreflightCheckType.STORAGE),
            blocked.blockers.map { it.type }.filter {
                it in setOf(
                    PreflightCheckType.BATTERY,
                    PreflightCheckType.CHARGING,
                    PreflightCheckType.STORAGE,
                )
            }.toSet(),
        )
    }

    @Test
    fun lowBatteryBlocksWhileKnownChargingAndStorageFailuresRemainAdvisory() {
        val checks = evaluator.evaluate(
            observation = observation(
                battery = failed("Battery is low."),
                charging = failed("Not charging."),
                storage = failed("Storage is low."),
            ),
            profiles = emptyList(),
        ).checks.associateBy { it.type }

        assertEquals(
            PreflightSeverity.BLOCKING,
            checks.getValue(PreflightCheckType.BATTERY).severity,
        )
        assertEquals(
            PreflightSeverity.WARNING,
            checks.getValue(PreflightCheckType.CHARGING).severity,
        )
        assertEquals(
            PreflightSeverity.WARNING,
            checks.getValue(PreflightCheckType.STORAGE).severity,
        )
    }

    @Test
    fun matchingSuccessfulRehearsalPassesButUnavailableWakeStillBlocksScheduling() {
        val environment = environment()
        val profile = profile(environment)
        val rehearsal = successfulRehearsal(profile)
        val report = evaluator.evaluate(
            observation = observation(
                cameraEnvironment = environment,
                deviceWake = failed("No wake implementation is configured."),
            ).copy(successfulRehearsals = mapOf(profile.id to rehearsal)),
            profiles = listOf(profile),
        )
        val checks = report.checks.associateBy { it.type }

        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.REHEARSAL_CURRENT).status)
        assertEquals(PreflightStatus.FAILED, checks.getValue(PreflightCheckType.DEVICE_WAKE).status)
        val blocked = assertInstanceOf(ScheduleReadiness.Blocked::class.java, report.readiness)
        assertEquals(listOf(PreflightCheckType.DEVICE_WAKE), blocked.blockers.map { it.type })
    }

    @Test
    fun rehearsalProofRemainsCurrentWhenAnotherCaptureAdvancesProfileTimestamp() {
        val environment = environment()
        val profile = profile(environment)
        val staleProof = successfulRehearsal(profile).copy(
            mediaSavedVerifiedAt = profile.verifiedAt?.minusSeconds(1),
        )

        val check = evaluator.evaluate(
            observation = observation(cameraEnvironment = environment).copy(
                successfulRehearsals = mapOf(profile.id to staleProof),
            ),
            profiles = listOf(profile),
        ).checks.single { it.type == PreflightCheckType.REHEARSAL_CURRENT }

        assertEquals(PreflightStatus.PASSED, check.status)
    }

    @Test
    fun rehearsalWithoutDurableReceiptDoesNotQualify() {
        val environment = environment()
        val profile = profile(environment)
        val withoutReceipt = successfulRehearsal(profile).copy(rehearsalVerifiedAt = null)

        val check = evaluator.evaluate(
            observation = observation(cameraEnvironment = environment).copy(
                successfulRehearsals = mapOf(profile.id to withoutReceipt),
            ),
            profiles = listOf(profile),
        ).checks.single { it.type == PreflightCheckType.REHEARSAL_CURRENT }

        assertEquals(PreflightStatus.FAILED, check.status)
    }

    @Test
    fun rehearsalForChangedProfileDefinitionDoesNotQualify() {
        val environment = environment()
        val profile = profile(environment)
        val staleDefinition = successfulRehearsal(profile).copy(
            executionKey = "rehearsal/rehearsal-1/${"0".repeat(64)}",
        )

        val check = evaluator.evaluate(
            observation = observation(cameraEnvironment = environment).copy(
                successfulRehearsals = mapOf(profile.id to staleDefinition),
            ),
            profiles = listOf(profile),
        ).checks.single { it.type == PreflightCheckType.REHEARSAL_CURRENT }

        assertEquals(PreflightStatus.FAILED, check.status)
    }

    @Test
    fun rehearsalWithoutSavedMediaDoesNotQualify() {
        val environment = environment()
        val profile = profile(environment)
        val withoutMedia = successfulRehearsal(profile).copy(mediaSavedVerifiedAt = null)

        val check = evaluator.evaluate(
            observation = observation(cameraEnvironment = environment).copy(
                successfulRehearsals = mapOf(profile.id to withoutMedia),
            ),
            profiles = listOf(profile),
        ).checks.single { it.type == PreflightCheckType.REHEARSAL_CURRENT }

        assertEquals(PreflightStatus.FAILED, check.status)
    }

    @Test
    fun environmentDriftAndSchemaDriftFailClosed() {
        val calibrated = environment()
        val fingerprintDrift = calibrated.copy(
            androidBuildFingerprint =
                "google/husky/husky:17/CP2A.260805.005/1:user/release-keys",
        )
        val versionDrift = calibrated.copy(cameraVersionCode = calibrated.cameraVersionCode + 1)
        val schemaDrift = profile(calibrated).copy(
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION + 1,
        )

        assertEquals(
            "No compatible profile is available for the current environment.",
            compatibilityMessage(fingerprintDrift, profile(calibrated)),
        )
        assertEquals(
            "No compatible profile is available for the current environment.",
            compatibilityMessage(versionDrift, profile(calibrated)),
        )
        assertEquals(
            "Available profiles are incompatible with the current environment.",
            compatibilityMessage(calibrated, schemaDrift),
        )
    }

    @Test
    fun profileForAnotherDeviceDoesNotQualify() {
        val current = environment()
        val otherDevice = current.copy(deviceModel = "Pixel 9 Pro")

        assertEquals(
            "No compatible profile is available for the current environment.",
            compatibilityMessage(current, profile(otherDevice)),
        )
    }

    @Test
    fun stableLookingBetaCarrierAndCustomEnvironmentsFailPreflightForPhysicalProfiles() {
        val stable = environment()
        val rejectedFingerprints = listOf(
            "google/husky/husky:17/CP41.260701.005/15834971:user/release-keys",
            "google/husky/husky:17/CP2A.260705.006.A1/15641321:user/release-keys",
            "google/husky/husky:17/CUSTOM.260705.006/1:user/release-keys",
            "google/husky/husky:17/CP2A.260705.006/1:user/release-keys",
        )

        rejectedFingerprints.forEach { fingerprint ->
            val rejected = stable.copy(androidBuildFingerprint = fingerprint)
            val check = evaluator.evaluate(
                observation = observation(cameraEnvironment = rejected),
                profiles = listOf(profile(stable)),
            ).checks.single { it.type == PreflightCheckType.PROFILE_COMPATIBILITY }

            assertEquals(PreflightStatus.FAILED, check.status, fingerprint)
            assertEquals(
                "No compatible profile is available for the current environment.",
                check.message,
            )
        }
    }

    private fun compatibilityMessage(
        current: PixelCameraEnvironment,
        profile: PixelCameraProfile,
    ): String = evaluator.evaluate(
        observation = observation(cameraEnvironment = current),
        profiles = listOf(profile),
    ).checks.single { it.type == PreflightCheckType.PROFILE_COMPATIBILITY }.message

    private fun observation(
        exactAlarms: RuntimeCapabilityObservation = passed("Exact alarms available."),
        notifications: RuntimeCapabilityObservation = passed("Notifications available."),
        mediaVideoAccess: RuntimeCapabilityObservation = passed("Saved video access available."),
        fullScreenIntent: RuntimeCapabilityObservation = passed("Full-screen intents available."),
        pixelCameraInstalled: RuntimeCapabilityObservation = passed("Pixel Camera installed."),
        cameraEnvironment: PixelCameraEnvironment? = environment(),
        secureCameraResolves: RuntimeCapabilityObservation = passed("Secure camera resolves."),
        deviceWake: RuntimeCapabilityObservation = passed("Device wake available."),
        accessibilityEnabled: RuntimeCapabilityObservation = passed("Accessibility enabled."),
        accessibilityConnected: RuntimeCapabilityObservation = passed("Accessibility connected."),
        battery: RuntimeCapabilityObservation = passed("Battery sufficient."),
        charging: RuntimeCapabilityObservation = passed("Charging."),
        storage: RuntimeCapabilityObservation = passed("Storage sufficient."),
    ) = RuntimePreflightObservation(
        exactAlarms = exactAlarms,
        notifications = notifications,
        mediaVideoAccess = mediaVideoAccess,
        fullScreenIntent = fullScreenIntent,
        pixelCameraInstalled = pixelCameraInstalled,
        cameraEnvironment = cameraEnvironment,
        secureCameraResolves = secureCameraResolves,
        deviceWake = deviceWake,
        accessibilityEnabled = accessibilityEnabled,
        accessibilityConnected = accessibilityConnected,
        battery = battery,
        charging = charging,
        storage = storage,
    )

    private fun profile(environment: PixelCameraEnvironment) = PixelCameraProfile(
        id = ProfileId("profile-${environment.deviceModel}"),
        environment = environment,
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = Instant.parse("2026-08-09T12:00:00Z"),
    )

    private fun successfulRehearsal(profile: PixelCameraProfile): ExecutionSession {
        val stoppedAt = checkNotNull(profile.verifiedAt)
        val startedAt = stoppedAt.minusSeconds(10)
        return ExecutionSession(
            id = SessionId("rehearsal-1"),
            executionKey = "rehearsal/rehearsal-1/${profile.definitionFingerprint()}",
            kind = SessionKind.REHEARSAL,
            scheduleId = null,
            scheduleName = "Rehearsal",
            profileId = profile.id,
            capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
            expectedStartAt = startedAt.minusSeconds(60),
            expectedStopAt = stoppedAt.plusSeconds(30),
            status = SessionStatus.COMPLETED,
            recordActionAt = startedAt,
            recordingVerifiedAt = startedAt,
            stopActionAt = stoppedAt,
            stoppedVerifiedAt = stoppedAt,
            mediaSavedVerifiedAt = stoppedAt,
            rehearsalVerifiedAt = stoppedAt,
            createdAt = startedAt.minusSeconds(60),
            updatedAt = stoppedAt,
        )
    }

    private fun environment() = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        deviceCodename = "husky",
        androidSdk = 37,
        androidBuildFingerprint =
            "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
        cameraPackage = SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName,
        cameraVersionCode = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode,
        cameraSigningCertificateSha256 =
            SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
        localeTag = "en-US",
        displayWidthPx = 1_008,
        displayHeightPx = 2_244,
        densityDpi = 360,
    )

    private fun passed(@Suppress("UNUSED_PARAMETER") message: String) = RuntimeCapabilityObservation(
        PreflightStatus.PASSED,
        localizedText(R.string.status_passed),
    )

    private fun failed(@Suppress("UNUSED_PARAMETER") message: String) = RuntimeCapabilityObservation(
        PreflightStatus.FAILED,
        localizedText(R.string.status_failed),
    )

    private fun unknown(@Suppress("UNUSED_PARAMETER") message: String) = RuntimeCapabilityObservation(
        PreflightStatus.UNKNOWN,
        localizedText(R.string.status_unknown),
    )
}
