package dev.po4yka.lenswake.integration

import android.Manifest
import android.content.pm.PackageManager
import android.database.MatrixCursor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.RecordingMediaBaseline
import dev.po4yka.lenswake.core.AutomationFailureCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRecordingMediaPortTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val deniedSubject = AndroidRecordingMediaPort(context) { false }

    @Test
    fun `baseline reports missing video read permission`() = runBlocking {
        val result = deniedSubject.captureBaseline()

        assertEquals(
            AutomationFailureCode.MEDIA_READ_PERMISSION_MISSING,
            (result as PortResult.Unavailable).failure.code,
        )
    }

    @Test
    fun `saved-media query reports missing video read permission`() = runBlocking {
        val result = deniedSubject.findSavedRecording(
            RecordingMediaBaseline(generation = 0, version = "version-1"),
        )

        assertEquals(
            AutomationFailureCode.MEDIA_READ_PERMISSION_MISSING,
            (result as PortResult.Unavailable).failure.code,
        )
    }

    @Test
    fun `adapter preserves coroutine cancellation`() {
        val cancelledSubject = AndroidRecordingMediaPort(context) {
            throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { cancelledSubject.captureBaseline() }
        }
    }

    @Test
    fun `multiple qualifying videos are rejected as ambiguous`() {
        val cursor = MatrixCursor(arrayOf("generation", "size", "duration")).apply {
            addRow(arrayOf(42L, 2_048L, 1_000L))
            addRow(arrayOf(43L, 4_096L, 2_000L))
        }

        val result = cursor.use(::uniqueSavedRecording)

        assertEquals(
            AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
            (result as PortResult.Unavailable).failure.code,
        )
    }

    @Test
    fun `granted adapter captures generation and finds no false Pixel Camera candidate`() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        assumeTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val subject = AndroidRecordingMediaPort(context)

        val baseline = subject.captureBaseline() as PortResult.Observed
        assertNotNull(baseline.value)
        val result = subject.findSavedRecording(baseline.value)

        assertNull((result as PortResult.Observed).value)

        val resetResult = subject.findSavedRecording(
            baseline.value.copy(version = "${baseline.value.version}-changed"),
        )
        assertEquals(
            AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
            (resetResult as PortResult.Unavailable).failure.code,
        )
    }
}
