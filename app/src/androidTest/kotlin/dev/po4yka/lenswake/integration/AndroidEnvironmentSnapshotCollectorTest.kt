package dev.po4yka.lenswake.integration

import android.content.res.Resources
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.DisplayOrientation
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY
import dev.po4yka.lenswake.platform.PixelCameraPackageIdentity
import dev.po4yka.lenswake.platform.SigningCertificateSha256
import dev.po4yka.lenswake.privileged.UnavailablePrivilegedBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AndroidEnvironmentSnapshotCollectorTest {
    @Test
    fun packageAccessUsesAndroidVersionResourcesAndSigningCertificateApis() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signerBytes = requireNotNull(packageInfo.signingInfo).apkContentsSigners.single().toByteArray()
        val signerSha256 = MessageDigest.getInstance("SHA-256")
            .digest(signerBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val identity = PixelCameraPackageIdentity(
            packageName = context.packageName,
            versionCode = packageInfo.longVersionCode,
            signingCertificate = SigningCertificateSha256(signerSha256),
        )
        val access = AndroidPixelCameraPackageAccess(context.packageManager, identity)

        assertEquals(packageInfo.longVersionCode, access.cameraVersionCode())
        assertNotNull(access.resources())
        assertTrue(access.hasSupportedSigningCertificate())
        assertTrue(
            !AndroidPixelCameraPackageAccess(
                context.packageManager,
                identity.copy(signingCertificate = SigningCertificateSha256("0".repeat(64))),
            ).hasSupportedSigningCertificate(),
        )
    }

    @Test
    fun collectsCompleteEnvironmentWhenPackageAndSignerAreSupported() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capturedAt = Instant.parse("2026-08-12T05:30:01Z")
        val cameraResources = context.resources
        val probe = AndroidPixelCameraEnvironmentProbe(
            context,
            TestPixelCameraPackageAccess(
                cameraResources = cameraResources,
                signerAccepted = true,
            ),
        )
        val collector = AndroidEnvironmentSnapshotCollector(
            context = context,
            cameraEnvironmentProbe = probe,
            privilegedBridge = UnavailablePrivilegedBridge(),
            clock = LenswakeClock { capturedAt },
        )

        val snapshot = collector.collect(
            snapshotId = EnvironmentSnapshotId("complete-environment"),
            sessionId = SessionId("complete-environment-session"),
        ).getOrThrow()

        with(snapshot.cameraEnvironment) {
            assertEquals(android.os.Build.DEVICE, deviceCodename)
            assertEquals(android.os.Build.FINGERPRINT, androidBuildFingerprint)
            assertEquals(SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName, cameraPackage)
            assertEquals(SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode, cameraVersionCode)
            assertEquals(
                SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
                cameraSigningCertificateSha256,
            )
            assertEquals(cameraResources.configuration.locales[0].toLanguageTag(), localeTag)
            assertEquals(context.resources.configuration.fontScale, fontScale)
            assertEquals(
                when (context.resources.configuration.orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> DisplayOrientation.PORTRAIT
                    Configuration.ORIENTATION_LANDSCAPE -> DisplayOrientation.LANDSCAPE
                    else -> error("The instrumentation display has no supported orientation")
                },
                orientation,
            )
            assertEquals(
                runCatching {
                    Settings.Global.getString(
                        context.contentResolver,
                        "display_size_forced",
                    ).isNullOrBlank() && Settings.Secure.getString(
                        context.contentResolver,
                        "display_density_forced",
                    ).isNullOrBlank()
                }.getOrDefault(false),
                defaultDisplayConfiguration,
            )
            assertTrue(displayWidthPx > 0)
            assertTrue(displayHeightPx > 0)
            assertTrue(densityDpi > 0)
        }
        assertEquals(capturedAt, snapshot.capturedAt)
    }

    @Test
    fun rejectsUnsupportedCameraSignerBeforeCollectingSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collector = AndroidEnvironmentSnapshotCollector(
            context = context,
            cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(
                context,
                TestPixelCameraPackageAccess(
                    cameraResources = context.resources,
                    signerAccepted = false,
                ),
            ),
            privilegedBridge = UnavailablePrivilegedBridge(),
            clock = LenswakeClock { Instant.parse("2026-08-12T05:30:01Z") },
        )

        val result = collector.collect(
            snapshotId = EnvironmentSnapshotId("wrong-signer"),
            sessionId = SessionId("wrong-signer-session"),
        )

        assertTrue(result.isFailure)
        assertEquals(
            "Pixel Camera is not signed by the supported Google certificate",
            result.exceptionOrNull()?.message,
        )
    }

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

    private class TestPixelCameraPackageAccess(
        private val cameraResources: Resources,
        private val signerAccepted: Boolean,
    ) : PixelCameraPackageAccess {
        override fun cameraVersionCode(): Long = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode

        override fun resources(): Resources = cameraResources

        override fun hasSupportedSigningCertificate(): Boolean = signerAccepted
    }
}
