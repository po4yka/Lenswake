package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionCameraOwnershipTest {
    private val now = Instant.parse("2026-08-10T10:00:00Z")

    @Test
    fun explicitRebootReleaseEndsUncertainOwnershipWithoutFabricatingStopVerification() {
        val uncertain = session(
            status = SessionStatus.FAILED,
            recordActionAt = now.minusSeconds(30),
        )

        assertTrue(uncertain.ownsPixelCamera)

        val released = uncertain.copy(
            failure = AutomationFailure(
                AutomationFailureCode.DEVICE_REBOOT_INTERRUPTED,
                "Device reboot interrupted recording",
            ),
            cameraOwnershipReleasedAt = now,
        )

        assertFalse(released.ownsPixelCamera)
        assertTrue(released.recordActionAt != null)
        assertTrue(released.stoppedVerifiedAt == null)
    }

    @Test
    fun verifiedStopNeverOwnsCameraEvenWithActiveStatus() {
        val inconsistentActive = session(
            status = SessionStatus.RECORDING,
            recordActionAt = now.minusSeconds(30),
            stoppedVerifiedAt = now.minusSeconds(1),
        )

        assertFalse(inconsistentActive.ownsPixelCamera)
    }

    private fun session(
        status: SessionStatus,
        recordActionAt: Instant? = null,
        stoppedVerifiedAt: Instant? = null,
    ) = ExecutionSession(
        id = SessionId("session"),
        executionKey = "execution",
        kind = SessionKind.SCHEDULED,
        scheduleId = ScheduleId("schedule"),
        scheduleName = "Schedule",
        profileId = ProfileId("profile"),
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        expectedStartAt = now.minusSeconds(60),
        expectedStopAt = now.plusSeconds(60),
        status = status,
        recordActionAt = recordActionAt,
        stoppedVerifiedAt = stoppedVerifiedAt,
        createdAt = now.minusSeconds(120),
        updatedAt = now.minusSeconds(30),
    )
}
