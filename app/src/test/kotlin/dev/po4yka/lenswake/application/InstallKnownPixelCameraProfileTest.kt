package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstallKnownPixelCameraProfileTest {
    private val candidate = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630

    @Test
    fun `exact environment is persisted and read back`() = runBlocking {
        val repository = FakeProfileRepository()
        val installer = installer(repository)

        val result = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.Installed::class.java,
            installer(),
        )

        assertEquals(candidate, result.profile)
        assertEquals(false, result.replacedExisting)
        assertEquals(1, repository.saveCount)
        assertEquals(2, repository.getCount)
        assertEquals(candidate, repository.profile)
    }

    @Test
    fun `repeated installation is idempotent`() = runBlocking {
        val repository = FakeProfileRepository()
        val installer = installer(repository)

        assertInstanceOf(InstallKnownPixelCameraProfileResult.Installed::class.java, installer())
        val repeated = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.AlreadyInstalled::class.java,
            installer(),
        )

        assertEquals(candidate, repeated.profile)
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun `existing rehearsed catalog profile is never downgraded`() = runBlocking {
        val verified = candidate.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T12:00:00Z"),
        )
        val repository = FakeProfileRepository(profile = verified)

        val result = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.AlreadyInstalled::class.java,
            installer(repository)(),
        )

        assertEquals(verified, result.profile)
        assertEquals(0, repository.saveCount)
        assertEquals(verified, repository.profile)
    }

    @Test
    fun `same stable id with stale definition is replaced and read back`() = runBlocking {
        val repository = FakeProfileRepository(
            profile = candidate.copy(
                targets = candidate.targets - AutomationAction.STOP_RECORDING,
            ),
        )

        val result = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.Installed::class.java,
            installer(repository)(),
        )

        assertTrue(result.replacedExisting)
        assertEquals(candidate, repository.profile)
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun `unsupported environment does not access persistence`() = runBlocking {
        val repository = FakeProfileRepository()
        val environment = candidate.environment.copy(deviceModel = "Pixel 9 Pro")
        val installer = InstallKnownPixelCameraProfile(
            environmentProbe = { PortResult.Observed(environment) },
            profileRepository = repository,
        )

        val result = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.UnsupportedEnvironment::class.java,
            installer(),
        )

        assertEquals(environment, result.environment)
        assertEquals(0, repository.getCount)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `environment probe failure is returned unchanged`() = runBlocking {
        val repository = FakeProfileRepository()
        val failure = AutomationFailure(
            code = AutomationFailureCode.PIXEL_CAMERA_NOT_INSTALLED,
            message = "Pixel Camera is unavailable",
        )
        val installer = InstallKnownPixelCameraProfile(
            environmentProbe = { PortResult.Unavailable(failure) },
            profileRepository = repository,
        )

        val result = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.EnvironmentUnavailable::class.java,
            installer(),
        )

        assertSame(failure, result.failure)
        assertEquals(0, repository.getCount)
    }

    @Test
    fun `environment probe cancellation is preserved`() {
        val installer = InstallKnownPixelCameraProfile(
            environmentProbe = { throw CancellationException("cancelled") },
            profileRepository = FakeProfileRepository(),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { installer() }
        }
    }

    @Test
    fun `repository failures identify their persistence stage`() = runBlocking {
        val readFailure = installer(FakeProfileRepository(failGetAt = 1))()
        val saveFailure = installer(FakeProfileRepository(failSave = true))()
        val readBackFailure = installer(FakeProfileRepository(failGetAt = 2))()

        assertPersistenceStage(ProfilePersistenceStage.READ_EXISTING, readFailure)
        assertPersistenceStage(ProfilePersistenceStage.SAVE, saveFailure)
        assertPersistenceStage(ProfilePersistenceStage.READ_BACK, readBackFailure)
    }

    @Test
    fun `missing read back is a typed persistence failure`() = runBlocking {
        val repository = FakeProfileRepository(dropSavedProfile = true)

        val result = installer(repository)()

        assertPersistenceStage(ProfilePersistenceStage.READ_BACK, result)
    }

    private fun installer(repository: FakeProfileRepository) = InstallKnownPixelCameraProfile(
        environmentProbe = { PortResult.Observed(candidate.environment) },
        profileRepository = repository,
    )

    private fun assertPersistenceStage(
        expected: ProfilePersistenceStage,
        result: InstallKnownPixelCameraProfileResult,
    ) {
        val failure = assertInstanceOf(
            InstallKnownPixelCameraProfileResult.PersistenceFailure::class.java,
            result,
        )
        assertEquals(expected, failure.stage)
    }

    private class FakeProfileRepository(
        profile: PixelCameraProfile? = null,
        private val failGetAt: Int? = null,
        private val failSave: Boolean = false,
        private val dropSavedProfile: Boolean = false,
    ) : AutomationProfileRepository {
        private val profiles = MutableStateFlow(listOfNotNull(profile))
        var getCount = 0
            private set
        var saveCount = 0
            private set

        val profile: PixelCameraProfile?
            get() = profiles.value.singleOrNull()

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override fun observePersistenceIssues(): Flow<List<dev.po4yka.lenswake.core.ProfilePersistenceIssue>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun get(id: ProfileId): PixelCameraProfile? {
            getCount += 1
            if (getCount == failGetAt) error("get failed")
            return profiles.value.singleOrNull { it.id == id }
        }

        override suspend fun save(profile: PixelCameraProfile) {
            saveCount += 1
            if (failSave) error("save failed")
            if (!dropSavedProfile) profiles.value = listOf(profile)
        }

        override suspend fun delete(id: ProfileId) {
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }
}
