package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleReadiness
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RuntimePreflightEvaluatorTest {
    private val evaluator = RuntimePreflightEvaluator()

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
    fun environmentDriftRequiresRehearsalAndSchemaDriftIsIncompatible() {
        val calibrated = environment()
        val fingerprintDrift = calibrated.copy(androidBuildFingerprint = "google/husky/new-build")
        val versionDrift = calibrated.copy(cameraVersionCode = calibrated.cameraVersionCode + 1)
        val schemaDrift = profile(calibrated).copy(
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION + 1,
        )

        assertEquals(
            "The closest profile requires a current-device rehearsal.",
            compatibilityMessage(fingerprintDrift, profile(calibrated)),
        )
        assertEquals(
            "The Pixel Camera environment changed; rehearsal is required.",
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

    private fun compatibilityMessage(
        current: PixelCameraEnvironment,
        profile: PixelCameraProfile,
    ): String = evaluator.evaluate(
        observation = observation(cameraEnvironment = current),
        profiles = listOf(profile),
    ).checks.single { it.type == PreflightCheckType.PROFILE_COMPATIBILITY }.message

    private fun observation(
        exactAlarms: RuntimeCapabilityObservation = passed("Exact alarms available."),
        pixelCameraInstalled: RuntimeCapabilityObservation = passed("Pixel Camera installed."),
        cameraEnvironment: PixelCameraEnvironment? = environment(),
        secureCameraResolves: RuntimeCapabilityObservation = passed("Secure camera resolves."),
        accessibilityEnabled: RuntimeCapabilityObservation = passed("Accessibility enabled."),
        accessibilityConnected: RuntimeCapabilityObservation = passed("Accessibility connected."),
    ) = RuntimePreflightObservation(
        exactAlarms = exactAlarms,
        pixelCameraInstalled = pixelCameraInstalled,
        cameraEnvironment = cameraEnvironment,
        secureCameraResolves = secureCameraResolves,
        accessibilityEnabled = accessibilityEnabled,
        accessibilityConnected = accessibilityConnected,
    )

    private fun profile(environment: PixelCameraEnvironment) = PixelCameraProfile(
        id = ProfileId("profile-${environment.deviceModel}"),
        environment = environment,
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = Instant.parse("2026-08-09T12:00:00Z"),
    )

    private fun environment() = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        androidSdk = 37,
        androidBuildFingerprint = "google/husky/build",
        cameraPackage = "com.google.android.GoogleCamera",
        cameraVersionCode = 69_481_630L,
        localeTag = "en-US",
        displayWidthPx = 1_008,
        displayHeightPx = 2_244,
        densityDpi = 360,
    )

    private fun passed(message: String) = RuntimeCapabilityObservation(PreflightStatus.PASSED, message)
    private fun failed(message: String) = RuntimeCapabilityObservation(PreflightStatus.FAILED, message)
    private fun unknown(message: String) = RuntimeCapabilityObservation(PreflightStatus.UNKNOWN, message)
}
