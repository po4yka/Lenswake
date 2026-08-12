package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfilePersistenceIssue
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.core.definitionFingerprint
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows

class InstallReleaseCertificationTest {
    private val candidate = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.copy(
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = Instant.parse("2026-08-12T10:00:00Z"),
    )

    @Test
    fun `signed exact receipt promotes only the accepted target and invalidates old fingerprint`() = runBlocking {
        val repository = FakeRepository(candidate)
        val result = installer(repository, bundle(candidate))("content://certification")

        val certified = assertInstanceOf(
            InstallReleaseCertificationResult.Certified::class.java,
            result,
        ).profile
        assertEquals(SupportTier.CERTIFIED, certified.supportTier)
        assertEquals(APK_SHA, certified.certification?.lenswakeApkSha256)
        assertEquals(ProfileCompatibility.VERIFIED, certified.compatibility)
        assertEquals(certified, repository.profile)
        assertEquals(false, certified.definitionFingerprint() == candidate.definitionFingerprint())
    }

    @Test
    fun `receipt for a different exact profile is rejected without persistence`() = runBlocking {
        val repository = FakeRepository(candidate)
        val mismatch = bundle(candidate).copy(
            receipt = bundle(candidate).receipt.copy(
                pixel8Pro = bundle(candidate).receipt.pixel8Pro.copy(
                    acceptedExperimentalProfileFingerprint = "9".repeat(64),
                ),
            ),
        )

        assertEquals(
            InstallReleaseCertificationResult.ProfileEvidenceMismatch,
            installer(repository, mismatch)("content://certification"),
        )
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `artifact-bound repository demotes certification after APK change`() = runBlocking {
        val delegate = FakeRepository(candidate)
        val certified = assertInstanceOf(
            InstallReleaseCertificationResult.Certified::class.java,
            installer(delegate, bundle(candidate))("content://certification"),
        ).profile
        val changedArtifact = ArtifactBoundAutomationProfileRepository(delegate) { "8".repeat(64) }

        val effective = checkNotNull(changedArtifact.get(certified.id))
        assertEquals(SupportTier.EXPERIMENTAL, effective.supportTier)
        assertEquals(null, effective.certification)
        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, effective.compatibility)
    }

    @Test
    fun `persistence cancellation is never converted to a certification failure`() {
        val repository = object : AutomationProfileRepository by FakeRepository(candidate) {
            override suspend fun save(profile: PixelCameraProfile) {
                throw CancellationException("cancelled")
            }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { installer(repository, bundle(candidate))("content://certification") }
        }
    }

    private fun installer(
        repository: AutomationProfileRepository,
        bundle: VerifiedReleaseCertificationBundle,
    ) = InstallReleaseCertification(
        bundleReader = ReleaseCertificationBundleReader { bundle },
        environmentProbe = { candidate.environment },
        profileRepository = repository,
    )

    private fun bundle(profile: PixelCameraProfile): VerifiedReleaseCertificationBundle =
        VerifiedReleaseCertificationBundle(
            receipt = ReleaseCertificationReceipt(
                releaseTag = "v1.2.3",
                releaseCommit = "1".repeat(40),
                candidateRunId = 123,
                apkFilename = "Lenswake-1.2.3.apk",
                apkSha256 = APK_SHA,
                pixel7 = CertifiedTargetReceipt(
                    "Pixel 7", "panther", "6".repeat(64),
                    "https://example.invalid/pixel-7", "4".repeat(64),
                ),
                pixel8Pro = CertifiedTargetReceipt(
                    "Pixel 8 Pro", "husky", profile.definitionFingerprint(),
                    "https://example.invalid/pixel-8-pro", "5".repeat(64),
                ),
            ),
            bundleSha256 = "3".repeat(64),
            installedApkSha256 = APK_SHA,
        )

    private class FakeRepository(initial: PixelCameraProfile) : AutomationProfileRepository {
        private val profiles = MutableStateFlow(listOf(initial))
        var saveCount = 0
            private set
        val profile get() = profiles.value.single()

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override fun observePersistenceIssues(): Flow<List<ProfilePersistenceIssue>> = flowOf(emptyList())
        override suspend fun get(id: ProfileId): PixelCameraProfile? = profiles.value.singleOrNull { it.id == id }
        override suspend fun save(profile: PixelCameraProfile) {
            saveCount += 1
            profiles.value = listOf(profile)
        }
        override suspend fun delete(id: ProfileId) {
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }

    private companion object {
        val APK_SHA = "2".repeat(64)
    }
}
