package dev.po4yka.lenswake.application

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.data.LenswakeDatabase
import dev.po4yka.lenswake.data.RoomAutomationProfileRepository
import dev.po4yka.lenswake.ui.AndroidUiStringProvider
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelCameraProfileInstallationInstrumentationTest {
    private val exactEnvironment =
        KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.environment

    private lateinit var database: LenswakeDatabase
    private lateinit var repository: RoomAutomationProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LenswakeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAutomationProfileRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactEnvironmentInstallsAndPassesExactProfilePreflight() = runBlocking {
        val installer = InstallKnownPixelCameraProfile(
            environmentProbe = { PortResult.Observed(exactEnvironment) },
            profileRepository = repository,
        )

        val consent = installer()
        assertTrue(consent is InstallKnownPixelCameraProfileResult.ExperimentalConsentRequired)

        val installed = installer(experimentalRiskAccepted = true)
        assertTrue(installed is InstallKnownPixelCameraProfileResult.Installed)
        val persisted = requireNotNull(repository.observeProfiles().first().singleOrNull())
        assertNotNull(persisted)
        assertEquals(exactEnvironment, persisted.environment)
        assertEquals(persisted, repository.get(persisted.id))

        val verified = persisted.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-12T12:00:00Z"),
        )
        val compatibility = evaluator().evaluate(
            observation = observation(exactEnvironment),
            profiles = listOf(verified),
        ).checks.single { it.type == PreflightCheckType.PROFILE_COMPATIBILITY }

        assertEquals(PreflightStatus.PASSED, compatibility.status)
    }

    @Test
    fun mismatchedCameraSignerCannotInstallOrPassPreflight() = runBlocking {
        val wrongSigner = exactEnvironment.copy(
            cameraSigningCertificateSha256 = "0".repeat(64),
        )
        val installer = InstallKnownPixelCameraProfile(
            environmentProbe = { PortResult.Observed(wrongSigner) },
            profileRepository = repository,
        )

        val install = installer(experimentalRiskAccepted = true)

        assertTrue(install is InstallKnownPixelCameraProfileResult.UnsupportedEnvironment)
        assertTrue(repository.observeProfiles().first().isEmpty())
        val verified = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-12T12:00:00Z"),
        )
        val compatibility = evaluator().evaluate(
            observation = observation(wrongSigner),
            profiles = listOf(verified),
        ).checks.single { it.type == PreflightCheckType.PROFILE_COMPATIBILITY }

        assertEquals(PreflightStatus.FAILED, compatibility.status)
    }

    private fun evaluator(): RuntimePreflightEvaluator = RuntimePreflightEvaluator(
        AndroidUiStringProvider(ApplicationProvider.getApplicationContext()),
    )

    private fun observation(environment: PixelCameraEnvironment): RuntimePreflightObservation {
        val passed = RuntimeCapabilityObservation(
            status = PreflightStatus.PASSED,
            message = localizedText(R.string.status_passed),
        )
        return RuntimePreflightObservation(
            exactAlarms = passed,
            notifications = passed,
            mediaVideoAccess = passed,
            fullScreenIntent = passed,
            pixelCameraInstalled = passed,
            cameraEnvironment = environment,
            secureCameraResolves = passed,
            deviceWake = passed,
            accessibilityEnabled = passed,
            accessibilityConnected = passed,
            battery = passed,
            charging = passed,
            storage = passed,
        )
    }

}
