package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmTransportPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun failureMarkerSurvivesStoreRecreationAndRemainsQueryable() {
        val preferenceName = "alarm-marker-test-${System.nanoTime()}"
        val first = SharedPreferencesAlarmTransportFailurePersistence(context, preferenceName)
        val marker = AlarmTransportFailureMarker(
            id = "delivery/stop",
            code = AlarmTransportFailureCode.RETRY_ATTEMPTS_EXHAUSTED,
            title = "Scheduled STOP needs manual action",
            message = "Open Pixel Camera and stop recording manually.",
            actionLabel = "Open Pixel Camera",
            cameraAction = true,
            recordedAtEpochMillis = 10_000L,
        )

        assertTrue(first.persist(marker))
        assertEquals(marker, SharedPreferencesAlarmTransportFailurePersistence(context, preferenceName).markers().single())
        assertTrue(first.remove(marker.id))
        assertTrue(first.markers().isEmpty())
    }

    @Test
    fun recoveryCheckpointSurvivesStoreRecreationUntilResolution() {
        val preferenceName = "alarm-recovery-checkpoint-test-${System.nanoTime()}"
        val first = SharedPreferencesAlarmRecoveryCheckpointPersistence(context, preferenceName)
        val checkpoint = AlarmRecoveryCheckpoint(
            attempt = 1,
            lastFailure = "Room unavailable",
            nextAttemptAtEpochMillis = 40_000L,
            exhausted = false,
            updatedAtEpochMillis = 10_000L,
        )

        assertTrue(first.persist(checkpoint))
        assertEquals(
            checkpoint,
            SharedPreferencesAlarmRecoveryCheckpointPersistence(context, preferenceName).checkpoint(),
        )
        assertTrue(first.clear())
        assertNull(first.checkpoint())
    }
}
