package dev.po4yka.lenswake.accessibility

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only, explicitly gated probe for a manually exposed Pixel Camera recording control. */
@RunWith(AndroidJUnit4::class)
class PhysicalPixelCameraSelectorProbeTest {
    @Test
    fun observeExpectedStopSelectorOnlyWhenExplicitlyRequested(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")
        val expectedDescription = requireNotNull(arguments.getString(ARG_DESCRIPTION)) {
            "$ARG_DESCRIPTION must name the expected stop control"
        }
        require(expectedDescription in ALLOWED_STOP_DESCRIPTIONS) {
            "$ARG_DESCRIPTION must be one of $ALLOWED_STOP_DESCRIPTIONS"
        }

        withTimeout(CONNECTION_TIMEOUT.toMillis()) {
            PixelCameraAccessibilityRuntime.connectionState.first { it }
        }
        val observed = awaitUniqueStopNode(expectedDescription)

        assertEquals(PIXEL_CAMERA_PACKAGE, observed.packageName)
        assertEquals("$PIXEL_CAMERA_PACKAGE:id/shutter_button", observed.resourceId)
        assertEquals(expectedDescription, observed.contentDescription)
        assertTrue(observed.clickable)
        assertTrue(observed.visible)
        assertTrue(observed.enabled)
        Log.i(LOG_TAG, "selector=$observed")
    }

    private suspend fun awaitUniqueStopNode(description: String): UiNodeSnapshot {
        var observed: UiNodeSnapshot? = null
        withTimeout(SNAPSHOT_TIMEOUT.toMillis()) {
            while (observed == null) {
                val snapshot = PixelCameraAccessibilityRuntime.snapshot()
                val matches = (snapshot as? AccessibilitySnapshotResult.Available)
                    ?.nodes
                    ?.filter { it.contentDescription == description }
                    .orEmpty()
                check(matches.size <= 1) {
                    "Expected a unique '$description' control; found ${matches.size}"
                }
                observed = matches.singleOrNull()
                if (observed == null) delay(SNAPSHOT_POLL.toMillis())
            }
        }
        return checkNotNull(observed)
    }

    private companion object {
        const val ARG_ENABLED = "physicalSelectorProbe"
        const val ARG_DESCRIPTION = "physicalSelectorExpectedDescription"
        const val LOG_TAG = "LenswakeSelectorProbe"
        val ALLOWED_STOP_DESCRIPTIONS = setOf("Stop video", "Stop time lapse")
        val CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(15)
        val SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val SNAPSHOT_POLL: Duration = Duration.ofMillis(250)
    }
}
