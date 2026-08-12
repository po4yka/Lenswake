package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RehearsalVerificationPolicyTest {
    private val verifiedAt = Instant.parse("2026-08-10T05:30:00Z")
    private val profile = PixelCameraProfile(
        id = ProfileId("profile"),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint = "google/husky/test",
            cameraPackage = "com.google.android.GoogleCamera",
            cameraVersionCode = 6_948_163_000,
            localeTag = "en-US",
            displayWidthPx = 1_344,
            displayHeightPx = 2_992,
            densityDpi = 489,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = verifiedAt,
    )
    private val capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30)

    @Test
    fun `durable proof qualifies only exact capture and current profile definition`() {
        val session = verifiedSession()

        assertTrue(RehearsalVerificationPolicy.qualifies(session, profile, capture))
        assertFalse(
            RehearsalVerificationPolicy.qualifies(
                session,
                profile,
                CaptureConfiguration.Video(),
            ),
        )
        assertFalse(
            RehearsalVerificationPolicy.qualifies(
                session,
                profile.copy(selectorSchemaVersion = profile.selectorSchemaVersion + 1),
                capture,
            ),
        )
    }

    @Test
    fun `video receipt is bound to exact resolution and frame rate`() {
        val video = CaptureConfiguration.Video()
        val session = verifiedSession(video)

        assertTrue(RehearsalVerificationPolicy.qualifies(session, profile, video))
        assertFalse(
            RehearsalVerificationPolicy.qualifies(
                session,
                profile,
                video.copy(frameRate = VideoFrameRate.LEGACY_UNKNOWN),
            ),
        )
    }

    @Test
    fun `profile promotion timestamp does not invalidate another exact capture receipt`() {
        val laterPromotion = profile.copy(verifiedAt = verifiedAt.plusSeconds(60))

        assertTrue(
            RehearsalVerificationPolicy.qualifies(
                verifiedSession(),
                laterPromotion,
                capture,
            ),
        )
    }

    @Test
    fun `receipt qualification requires full failure-free proof before receipt is issued`() {
        val beforeReceipt = verifiedSession().copy(rehearsalVerifiedAt = null)

        assertNull(RehearsalVerificationPolicy.receiptQualificationFailure(beforeReceipt, profile))
        assertTrue(RehearsalVerificationPolicy.awaitsDurableReceipt(beforeReceipt))
        assertEquals(
            RehearsalVerificationFailure.RECEIPT_MISSING,
            RehearsalVerificationPolicy.qualificationFailure(beforeReceipt, profile, capture),
        )
        assertEquals(
            RehearsalVerificationFailure.EXECUTION_FAILED,
            RehearsalVerificationPolicy.receiptQualificationFailure(
                beforeReceipt.copy(
                    failure = AutomationFailure(AutomationFailureCode.UNKNOWN, "failed"),
                ),
                profile,
            ),
        )
    }

    @Test
    fun `durable receipt cannot hide incomplete proof`() {
        val incomplete = verifiedSession().copy(stopActionAt = null)

        assertFalse(RehearsalVerificationPolicy.hasDurableReceipt(incomplete))
        assertEquals(
            RehearsalVerificationFailure.STOP_ACTION_MISSING,
            RehearsalVerificationPolicy.qualificationFailure(incomplete, profile, capture),
        )
    }

    private fun verifiedSession(
        testedCapture: CaptureConfiguration = capture,
    ): ExecutionSession = ExecutionSession(
        id = SessionId("rehearsal"),
        executionKey = "rehearsal/rehearsal/${profile.definitionFingerprint()}",
        kind = SessionKind.REHEARSAL,
        scheduleId = null,
        scheduleName = "Rehearsal",
        profileId = profile.id,
        capture = testedCapture,
        expectedStartAt = verifiedAt.minusSeconds(70),
        expectedStopAt = verifiedAt.minusSeconds(10),
        status = SessionStatus.COMPLETED,
        recordActionAt = verifiedAt.minusSeconds(60),
        recordingVerifiedAt = verifiedAt.minusSeconds(59),
        stopActionAt = verifiedAt.minusSeconds(2),
        stoppedVerifiedAt = verifiedAt.minusSeconds(1),
        mediaSavedVerifiedAt = verifiedAt,
        rehearsalVerifiedAt = verifiedAt,
        createdAt = verifiedAt.minusSeconds(70),
        updatedAt = verifiedAt,
    )
}
