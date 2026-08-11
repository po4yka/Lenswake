package dev.po4yka.lenswake.alarm

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.application.AlarmTransportIncidentAction
import dev.po4yka.lenswake.application.SharedPreferencesAlarmTransportIncidentSource
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
        val restored = SharedPreferencesAlarmTransportFailurePersistence(context, preferenceName)
        assertEquals(marker, restored.markers().single())
        assertTrue(first.isDeviceProtectedStorage)
        assertTrue(first.remove(marker.id))
        assertTrue(first.markers().isEmpty())
    }

    @Test
    fun diagnosticsIncidentSurvivesRecreationAndDisappearsOnlyAfterPersistenceResolution() {
        val preferenceName = "alarm-incident-test-${System.nanoTime()}"
        val persistence = SharedPreferencesAlarmTransportFailurePersistence(context, preferenceName)
        val marker = AlarmTransportFailureMarker(
            id = "delivery/stop",
            code = AlarmTransportFailureCode.STOP_TERMINAL_REJECTED,
            title = "Scheduled STOP needs manual action",
            message = "Camera may still be recording.",
            actionLabel = "Open Pixel Camera",
            cameraAction = true,
            recordedAtEpochMillis = 10_000L,
        )

        assertTrue(persistence.persist(marker))
        val recreated = SharedPreferencesAlarmTransportIncidentSource(context, preferenceName)
        assertEquals(marker.id, recreated.incidents.value.single().id)
        assertEquals(AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA, recreated.incidents.value.single().action)

        // A UI action does not mutate this store; only the alarm recovery resolution does.
        assertTrue(persistence.remove(marker.id))
        assertTrue(recreated.incidents.value.isEmpty())
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
            reconcileInterruptedSessions = true,
        )

        assertTrue(first.persist(checkpoint))
        assertEquals(
            checkpoint,
            SharedPreferencesAlarmRecoveryCheckpointPersistence(context, preferenceName).checkpoint(),
        )
        assertTrue(first.clear())
        assertNull(first.checkpoint())
    }

    @Test
    fun recoveryCheckpointUsesDeviceProtectedStorage() {
        val persistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(
            context,
            "device-protected-recovery-${System.nanoTime()}",
        )

        assertTrue(persistence.isDeviceProtectedStorage)
    }

    @Test
    fun legacyRecoveryCheckpointRemainsReadableWithoutInventingRebootReconciliation() {
        val preferenceName = "legacy-recovery-checkpoint-${System.nanoTime()}"
        val encodedFailure = Base64.encodeToString(
            "legacy failure".toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .edit()
            .putString("checkpoint", "1|1|40000|false|10000|$encodedFailure")
            .commit()

        val checkpoint = SharedPreferencesAlarmRecoveryCheckpointPersistence(
            context,
            preferenceName,
        ).checkpoint()

        assertEquals("legacy failure", checkpoint?.lastFailure)
        assertEquals(false, checkpoint?.reconcileInterruptedSessions)
    }
}
