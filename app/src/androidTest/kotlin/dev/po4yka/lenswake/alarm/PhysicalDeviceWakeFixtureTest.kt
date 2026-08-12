package dev.po4yka.lenswake.alarm

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.LenswakeApplication
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfileResult
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.ScheduleCommand
import dev.po4yka.lenswake.application.ScheduleWorkflowResult
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.platform.PlatformCapability
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit physical-device fixture for proving that a persisted RTC_WAKEUP alarm reaches the
 * DEVICE_WAKE production path. It never arms an alarm unless `physicalWake=true` is supplied.
 *
 * This fixture intentionally leaves the schedule and both independent alarms armed so the device
 * can be locked after instrumentation exits. Remove them only with `physicalWakeCleanup=true`.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceWakeFixtureTest {
    private val application = ApplicationProvider.getApplicationContext<LenswakeApplication>()

    @Test
    fun wakeLockedDisplayOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        assumeTrue(
            "Physical display-wake proof is disabled; pass -e physicalDeviceWakeOnly true to run it",
            instrumentationArgument("physicalDeviceWakeOnly") == "true",
        )
        val powerManager = application.getSystemService(PowerManager::class.java)
        val keyguardManager = application.getSystemService(KeyguardManager::class.java)

        assertFalse("DEVICE_WAKE proof must begin with a non-interactive display", powerManager.isInteractive)
        assertTrue("DEVICE_WAKE proof must begin with keyguard locked", keyguardManager.isDeviceLocked)

        val result = AndroidDeviceWakeController(application).wakeDevice()

        assertTrue("Production DEVICE_WAKE failed: $result", result is PlatformCapability.Available)
        assertTrue("DEVICE_WAKE returned before the display became interactive", powerManager.isInteractive)
        assertTrue("DEVICE_WAKE must not dismiss keyguard", keyguardManager.isDeviceLocked)
        Log.i(
            LOG_TAG,
            "Physical DEVICE_WAKE passed " +
                "interactive=${powerManager.isInteractive} deviceLocked=${keyguardManager.isDeviceLocked}",
        )
    }

    @Test
    fun armPersistedStartAndStopAlarmsForPhysicalDeviceWakeProof(): Unit = runBlocking {
        requirePhysicalWakeArming()
        val graph = application.graph
        awaitAccessibilityReconnect()
        val profile = requireCurrentVerifiedProfileWithRehearsal()
        val timing = physicalWakeTiming()
        val startAt = graph.clock.now().plusSeconds(timing.startDelaySeconds)
        val stopAt = startAt.plusSeconds(timing.recordingWindowSeconds)
        val result = graph.scheduleWorkflow.create(
            ScheduleCommand(
                name = FIXTURE_SCHEDULE_NAME,
                startAt = startAt,
                stopAt = stopAt,
                zoneId = ZoneId.systemDefault(),
                capture = CaptureConfiguration.TimeLapse(
                    speed = TimeLapseSpeed.X120,
                    lens = LensSelection.REAR_MAIN,
                ),
                profileId = profile.id,
                enabled = true,
            ),
        )
        val applied = result as? ScheduleWorkflowResult.Applied
            ?: error("DEVICE_WAKE fixture schedule was not armed by ScheduleWorkflow: $result")
        val schedule = applied.schedule
        assertEquals(FIXTURE_SCHEDULE_NAME, schedule.name)
        assertEquals(profile.id, schedule.profileId)
        assertEquals(startAt, schedule.startAt)
        assertEquals(stopAt, schedule.stopAt)
        assertTrue(schedule.enabled)

        Log.i(
            LOG_TAG,
            "DEVICE_WAKE fixture armed " +
                "scheduleId=${schedule.id.value} profileId=${profile.id.value} " +
                "createdAt=${schedule.createdAt} sessionStartAt=${schedule.startAt} sessionStopAt=${schedule.stopAt} " +
                "startDelaySeconds=${timing.startDelaySeconds} " +
                "recordingWindowSeconds=${timing.recordingWindowSeconds}",
        )
    }

    @Test
    fun runPhysicalProfileRehearsalOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        requirePhysicalProfileRehearsal()
        val graph = application.graph
        awaitAccessibilityReconnect()
        val profile = when (val install = graph.installKnownPixelCameraProfile()) {
            is InstallKnownPixelCameraProfileResult.Installed -> install.profile
            is InstallKnownPixelCameraProfileResult.AlreadyInstalled -> install.profile
            else -> error("Physical rehearsal requires an exact known Pixel Camera profile: $install")
        }
        val result = graph.rehearsalCoordinator.run(
            RehearsalRequest(
                profileId = profile.id,
                capture = CaptureConfiguration.TimeLapse(
                    speed = TimeLapseSpeed.X120,
                    lens = LensSelection.REAR_MAIN,
                ),
                recordingDuration = Duration.ofSeconds(10),
            ),
        )
        val completed = result as? RehearsalResult.Completed
            ?: error("Physical profile rehearsal did not pass: $result")
        val persistedProfile = checkNotNull(graph.profileRepository.get(profile.id)) {
            "Physical rehearsal removed its profile unexpectedly"
        }

        assertEquals(ProfileCompatibility.VERIFIED, completed.verifiedProfile.compatibility)
        assertEquals(ProfileCompatibility.VERIFIED, persistedProfile.compatibility)
        assertEquals(completed.verifiedProfile, persistedProfile)
        assertEquals(completed.session.profileId, persistedProfile.id)
        assertNotNull("Passed rehearsal must verify recording", completed.session.recordingVerifiedAt)
        assertNotNull("Passed rehearsal must verify stop", completed.session.stoppedVerifiedAt)
        Log.i(
            LOG_TAG,
            "Physical profile rehearsal passed " +
                "sessionId=${completed.session.id.value} profileId=${persistedProfile.id.value} " +
                "profileCompatibility=${persistedProfile.compatibility} " +
                "profileVerifiedAt=${persistedProfile.verifiedAt} " +
                "sessionExpectedStartAt=${completed.session.expectedStartAt} " +
                "sessionExpectedStopAt=${completed.session.expectedStopAt} " +
                "recordingVerifiedAt=${completed.session.recordingVerifiedAt} " +
                "stoppedVerifiedAt=${completed.session.stoppedVerifiedAt}",
        )
    }

    @Test
    fun cleanupPersistedPhysicalDeviceWakeFixtureOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        assumeTrue(
            "Refusing to remove physical DEVICE_WAKE fixture without physicalWakeCleanup=true",
            instrumentationArgument("physicalWakeCleanup") == "true",
        )
        val graph = application.graph
        val requestedScheduleId = instrumentationArgument("physicalWakeScheduleId")?.let(::ScheduleId)
        val ownedSchedules = graph.scheduleRepository.observeSchedules().first()
            .filter { it.name == FIXTURE_SCHEDULE_NAME }
        val matchingSchedules = if (requestedScheduleId == null) {
            ownedSchedules
        } else {
            ownedSchedules.filter { it.id == requestedScheduleId }
        }
        require(matchingSchedules.size <= 1) {
            "Refusing to delete physical DEVICE_WAKE fixtures: ${matchingSchedules.size} schedules match the cleanup request"
        }
        val schedule = matchingSchedules.singleOrNull()
            ?: if (requestedScheduleId == null) {
                return@runBlocking
            } else {
                error("No owned physical DEVICE_WAKE fixture has id ${requestedScheduleId.value}")
            }
        require(schedule.profileId == CURRENT_PROFILE.id && schedule.enabled) {
            "Refusing to delete schedule ${schedule.id.value}: fixture ownership data does not match"
        }
        require(
            schedule.capture == CaptureConfiguration.TimeLapse(
                speed = TimeLapseSpeed.X120,
                lens = LensSelection.REAR_MAIN,
            ),
        ) {
            "Refusing to delete schedule ${schedule.id.value}: fixture capture data does not match"
        }
        val result = graph.scheduleWorkflow.delete(schedule.id)
        check(result is ScheduleWorkflowResult.Deleted && result.scheduleId == schedule.id) {
            "DEVICE_WAKE fixture schedule was not deleted by ScheduleWorkflow: $result"
        }
        Log.i(LOG_TAG, "DEVICE_WAKE fixture removed scheduleId=${schedule.id.value}")
    }

    private fun requirePhysicalWakeArming() {
        assumeTrue(
            "Physical DEVICE_WAKE fixture is disabled; pass -e physicalWake true to arm it",
            instrumentationArgument("physicalWake") == "true",
        )
    }

    private fun requirePhysicalProfileRehearsal() {
        assumeTrue(
            "Physical profile rehearsal is disabled; pass -e physicalProfileRehearsal true to run it",
            instrumentationArgument("physicalProfileRehearsal") == "true",
        )
    }

    private suspend fun awaitAccessibilityReconnect() {
        val connected = withTimeoutOrNull(ACCESSIBILITY_REBIND_TIMEOUT.toMillis()) {
            PixelCameraAccessibilityRuntime.connectionState.first { it }
        }
        check(connected == true) {
            "Accessibility Service did not reconnect within ${ACCESSIBILITY_REBIND_TIMEOUT.seconds} seconds; " +
                "enable Lenswake Accessibility Service after instrumentation starts, then retry"
        }
    }

    private suspend fun requireCurrentVerifiedProfileWithRehearsal() = application.graph.let { graph ->
        val profile = checkNotNull(graph.profileRepository.get(CURRENT_PROFILE.id)) {
            "Current v4 Pixel Camera profile is not installed; complete the physical rehearsal first"
        }
        check(profile == CURRENT_PROFILE.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = profile.verifiedAt,
        )) {
            "Installed profile is not the exact current v4 Pixel Camera profile"
        }
        check(profile.compatibility == ProfileCompatibility.VERIFIED && profile.verifiedAt != null) {
            "Current v4 Pixel Camera profile must be VERIFIED with a verification timestamp"
        }
        val rehearsal = checkNotNull(graph.executionRepository.latestSuccessfulRehearsal(profile.id)) {
            "Current v4 Pixel Camera profile has no qualifying successful rehearsal"
        }
        check(
            rehearsal.kind == SessionKind.REHEARSAL &&
                rehearsal.status == SessionStatus.COMPLETED &&
                rehearsal.recordingVerifiedAt != null &&
                rehearsal.stoppedVerifiedAt != null &&
                rehearsal.mediaSavedVerifiedAt == profile.verifiedAt,
        ) {
            "Current v4 Pixel Camera profile rehearsal evidence is not qualifying"
        }
        profile
    }

    private fun physicalWakeTiming(): PhysicalWakeTiming = PhysicalWakeTiming(
        startDelaySeconds = boundedArgument(
            name = "physicalWakeStartDelaySeconds",
            defaultValue = DEFAULT_START_DELAY_SECONDS,
            allowed = START_DELAY_SECONDS_RANGE,
        ),
        recordingWindowSeconds = boundedArgument(
            name = "physicalWakeRecordingWindowSeconds",
            defaultValue = DEFAULT_RECORDING_WINDOW_SECONDS,
            allowed = RECORDING_WINDOW_SECONDS_RANGE,
        ),
    )

    private fun boundedArgument(
        name: String,
        defaultValue: Long,
        allowed: LongRange,
    ): Long {
        val raw = instrumentationArgument(name) ?: return defaultValue
        val value = raw.toLongOrNull()
            ?: error("$name must be an integer in ${allowed.first}..${allowed.last}; was '$raw'")
        require(value in allowed) {
            "$name must be in ${allowed.first}..${allowed.last}; was $value"
        }
        return value
    }

    private fun instrumentationArgument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private companion object {
        const val LOG_TAG = "LenswakePhysicalWake"
        // This persisted identity predates selector schema v4 and must remain stable for cleanup.
        const val FIXTURE_SCHEDULE_NAME = "Lenswake physical DEVICE_WAKE proof v3"
        const val DEFAULT_START_DELAY_SECONDS = 120L
        const val DEFAULT_RECORDING_WINDOW_SECONDS = 120L
        val START_DELAY_SECONDS_RANGE = 120L..900L
        val RECORDING_WINDOW_SECONDS_RANGE = 60L..300L
        val ACCESSIBILITY_REBIND_TIMEOUT: Duration = Duration.ofSeconds(30)
        val CURRENT_PROFILE = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630
    }

    private data class PhysicalWakeTiming(
        val startDelaySeconds: Long,
        val recordingWindowSeconds: Long,
    )
}
