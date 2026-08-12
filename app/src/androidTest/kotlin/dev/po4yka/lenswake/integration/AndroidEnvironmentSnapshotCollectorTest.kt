package dev.po4yka.lenswake.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.privileged.UnavailablePrivilegedBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AndroidEnvironmentSnapshotCollectorTest {
    @Test
    fun capturesBoundedOperationalStateOrReportsCameraUnavailability() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capturedAt = Instant.parse("2026-08-10T05:30:01Z")
        val cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(context)
        val cameraInspection = cameraEnvironmentProbe.inspect()
        val collector = AndroidEnvironmentSnapshotCollector(
            context = context,
            cameraEnvironmentProbe = cameraEnvironmentProbe,
            privilegedBridge = UnavailablePrivilegedBridge(),
            clock = LenswakeClock { capturedAt },
        )

        val result = collector.collect(
            snapshotId = EnvironmentSnapshotId("snapshot"),
            sessionId = SessionId("session"),
        )

        when (cameraInspection) {
            is PortResult.Unavailable -> {
                assertTrue(result.isFailure)
                assertEquals(cameraInspection.failure.message, result.exceptionOrNull()?.message)
                return@runBlocking
            }

            is PortResult.Observed -> assertTrue(result.isSuccess)
        }
        val snapshot = result.getOrThrow()
        assertEquals(capturedAt, snapshot.capturedAt)
        assertEquals("session", snapshot.sessionId.value)
        assertTrue(snapshot.lenswakeVersion.isNotBlank())
        assertTrue(snapshot.cameraEnvironment.cameraVersionCode >= 0)
        assertTrue(snapshot.cameraEnvironment.displayWidthPx > 0)
        assertTrue(snapshot.cameraEnvironment.displayHeightPx > 0)
        assertEquals(EnvironmentCapabilityStatus.UNAVAILABLE, snapshot.privilegedBridgeStatus)
        assertTrue(snapshot.batteryPercent == null || snapshot.batteryPercent in 0..100)
        assertNotNull(snapshot.availableStorageBytes)
    }
}
