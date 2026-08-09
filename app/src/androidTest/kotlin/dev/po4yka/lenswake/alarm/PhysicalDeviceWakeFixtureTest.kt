package dev.po4yka.lenswake.alarm

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.LenswakeApplication
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfileResult
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun armPersistedStartAndStopAlarmsForPhysicalDeviceWakeProof(): Unit = runBlocking {
        requirePhysicalWakeArming()
        val graph = application.graph
        val profile = when (val install = graph.installKnownPixelCameraProfile()) {
            is InstallKnownPixelCameraProfileResult.Installed -> install.profile
            is InstallKnownPixelCameraProfileResult.AlreadyInstalled -> install.profile
            else -> error("DEVICE_WAKE fixture requires an exact known Pixel Camera profile: $install")
        }
        assertNotNull("Installed profile must be readable through the production repository", graph.profileRepository.get(profile.id))

        // Whole-second values make the emitted proof record deterministic and easy to compare.
        val createdAt = graph.clock.now().truncatedTo(ChronoUnit.SECONDS)
        val startAt = createdAt.plusSeconds(START_DELAY_SECONDS)
        val stopAt = startAt.plusSeconds(RECORDING_WINDOW_SECONDS)
        val schedule = RecordingSchedule(
            id = FIXTURE_SCHEDULE_ID,
            name = FIXTURE_SCHEDULE_NAME,
            startAt = startAt,
            stopAt = stopAt,
            zoneId = ZoneId.systemDefault(),
            capture = CaptureConfiguration.TimeLapse(speed = TimeLapseSpeed.X120),
            profileId = profile.id,
            enabled = true,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        // Cancel only this fixture identity before replacing its persisted revision.
        graph.recordingScheduler.cancel(FIXTURE_SCHEDULE_ID).getOrThrow()
        graph.scheduleRepository.save(schedule)
        assertEquals(schedule, graph.scheduleRepository.get(FIXTURE_SCHEDULE_ID))
        graph.recordingScheduler.scheduleStart(schedule).getOrThrow()
        graph.recordingScheduler.scheduleStop(schedule).getOrThrow()

        Log.i(
            LOG_TAG,
            "DEVICE_WAKE fixture armed " +
                "scheduleId=${schedule.id.value} profileId=${profile.id.value} " +
                "createdAt=$createdAt sessionStartAt=$startAt sessionStopAt=$stopAt " +
                "startDelaySeconds=$START_DELAY_SECONDS recordingWindowSeconds=$RECORDING_WINDOW_SECONDS",
        )
        Unit
    }

    @Test
    fun runPhysicalProfileRehearsalOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        requirePhysicalProfileRehearsal()
        val graph = application.graph
        val connected = withTimeoutOrNull(ACCESSIBILITY_REBIND_TIMEOUT.toMillis()) {
            PixelCameraAccessibilityRuntime.connectionState.first { it }
        }
        check(connected == true) {
            "Accessibility Service did not reconnect within ${ACCESSIBILITY_REBIND_TIMEOUT.seconds} seconds; " +
                "enable Lenswake Accessibility Service after instrumentation starts, then retry"
        }
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
        val persisted = graph.scheduleRepository.get(FIXTURE_SCHEDULE_ID) ?: return@runBlocking
        require(persisted.name == FIXTURE_SCHEDULE_NAME) {
            "Refusing to delete schedule ${FIXTURE_SCHEDULE_ID.value}: fixture ownership marker is absent"
        }

        graph.recordingScheduler.cancel(FIXTURE_SCHEDULE_ID).getOrThrow()
        graph.scheduleRepository.delete(FIXTURE_SCHEDULE_ID)
        Log.i(LOG_TAG, "DEVICE_WAKE fixture removed scheduleId=${FIXTURE_SCHEDULE_ID.value}")
        Unit
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

    private fun instrumentationArgument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private companion object {
        const val LOG_TAG = "LenswakePhysicalWake"
        const val FIXTURE_SCHEDULE_NAME = "Lenswake physical DEVICE_WAKE proof"
        const val START_DELAY_SECONDS = 120L
        const val RECORDING_WINDOW_SECONDS = 120L
        val ACCESSIBILITY_REBIND_TIMEOUT: Duration = Duration.ofSeconds(30)
        val FIXTURE_SCHEDULE_ID = ScheduleId("physical-device-wake-proof-v1")
    }
}
