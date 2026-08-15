package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCertification
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.core.definitionFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CertifiedTargetReceipt(
    val model: String,
    val codename: String,
    val acceptedExperimentalProfileFingerprint: String,
    val evidenceUrl: String,
    val evidenceSha256: String,
)

data class ReleaseCertificationReceipt(
    val releaseTag: String,
    val releaseCommit: String,
    val candidateRunId: Long,
    val apkFilename: String,
    val apkSha256: String,
    val pixel7: CertifiedTargetReceipt,
    val pixel8Pro: CertifiedTargetReceipt,
)

data class VerifiedReleaseCertificationBundle(
    val receipt: ReleaseCertificationReceipt,
    val bundleSha256: String,
    val installedApkSha256: String,
)

fun interface ReleaseCertificationBundleReader {
    fun read(uri: String): VerifiedReleaseCertificationBundle
}

sealed interface InstallReleaseCertificationResult {
    data class Certified(val profile: PixelCameraProfile) : InstallReleaseCertificationResult
    data class AlreadyCertified(val profile: PixelCameraProfile) : InstallReleaseCertificationResult
    data object ProfileRequired : InstallReleaseCertificationResult
    data object UnsupportedTarget : InstallReleaseCertificationResult
    data object ProfileEvidenceMismatch : InstallReleaseCertificationResult
    data object InvalidBundle : InstallReleaseCertificationResult
    data class PersistenceFailure(val stage: ProfilePersistenceStage) : InstallReleaseCertificationResult
}

/** Promotes only an exact accepted Experimental profile through a signed, APK-bound release receipt. */
class InstallReleaseCertification(
    private val bundleReader: ReleaseCertificationBundleReader,
    private val environmentProbe: () -> PixelCameraEnvironment?,
    private val profileRepository: AutomationProfileRepository,
) {
    suspend operator fun invoke(uri: String): InstallReleaseCertificationResult =
        when (val preparation = prepare(uri)) {
            is CertificationPreparation.Rejected -> preparation.result
            is CertificationPreparation.Ready -> applyCertification(preparation)
        }

    private suspend fun prepare(uri: String): CertificationPreparation = when (val read = readBundle(uri)) {
        is BundleRead.Rejected -> CertificationPreparation.Rejected(read.result)
        is BundleRead.Verified -> resolveTarget(read.bundle)
    }

    private fun readBundle(uri: String): BundleRead = try {
        val bundle = bundleReader.read(uri)
        if (bundle.receipt.apkSha256 == bundle.installedApkSha256) {
            BundleRead.Verified(bundle)
        } else {
            BundleRead.Rejected(InstallReleaseCertificationResult.InvalidBundle)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BundleRead.Rejected(InstallReleaseCertificationResult.InvalidBundle)
    }

    private suspend fun resolveTarget(bundle: VerifiedReleaseCertificationBundle): CertificationPreparation {
        val environment = environmentProbe()
        val model = environment?.let { current ->
            SupportedPixelModelRegistry.find(
                current.deviceManufacturer,
                current.deviceModel,
                current.deviceCodename,
            )?.takeIf(SupportedPixelModel::certificationTarget)
        }
        val target = model?.let { supported -> bundle.receipt.targetFor(supported.codename) }
        val expected = environment?.let(KnownPixelCameraProfileCatalog::exactMatch)
        return when {
            model == null || target == null || expected == null ->
                CertificationPreparation.Rejected(InstallReleaseCertificationResult.UnsupportedTarget)
            target.model != model.model || target.codename != model.codename ->
                CertificationPreparation.Rejected(InstallReleaseCertificationResult.InvalidBundle)
            else -> readExisting(bundle, target, expected)
        }
    }

    private suspend fun readExisting(
        bundle: VerifiedReleaseCertificationBundle,
        target: CertifiedTargetReceipt,
        expected: PixelCameraProfile,
    ): CertificationPreparation = try {
        profileRepository.get(expected.id)?.let { existing ->
            CertificationPreparation.Ready(bundle, target, expected, existing)
        } ?: CertificationPreparation.Rejected(InstallReleaseCertificationResult.ProfileRequired)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CertificationPreparation.Rejected(
            InstallReleaseCertificationResult.PersistenceFailure(ProfilePersistenceStage.READ_EXISTING),
        )
    }

    private suspend fun applyCertification(
        preparation: CertificationPreparation.Ready,
    ): InstallReleaseCertificationResult = when {
        CertificationProfilePolicy.hasCertification(preparation.existing, preparation.bundle) ->
            InstallReleaseCertificationResult.AlreadyCertified(preparation.existing)
        !CertificationProfilePolicy.matchesAcceptedExperimentalProfile(
            preparation.existing,
            preparation.target,
            preparation.expected,
        ) -> InstallReleaseCertificationResult.ProfileEvidenceMismatch
        else -> persistCertified(CertificationProfilePolicy.certifiedBy(preparation.existing, preparation.bundle))
    }

    private suspend fun persistCertified(certified: PixelCameraProfile): InstallReleaseCertificationResult {
        val save = captureCertificationPersistence { profileRepository.save(certified) }
        return if (save is CertificationPersistence.Failed) {
            InstallReleaseCertificationResult.PersistenceFailure(ProfilePersistenceStage.SAVE)
        } else {
            verifyReadBack(certified)
        }
    }

    private suspend fun verifyReadBack(certified: PixelCameraProfile): InstallReleaseCertificationResult {
        val readBack = captureCertificationPersistence { profileRepository.get(certified.id) }
        return when {
            readBack is CertificationPersistence.Failed ->
                InstallReleaseCertificationResult.PersistenceFailure(ProfilePersistenceStage.READ_BACK)
            readBack is CertificationPersistence.Succeeded && readBack.value == certified ->
                InstallReleaseCertificationResult.Certified(certified)
            else -> InstallReleaseCertificationResult.PersistenceFailure(ProfilePersistenceStage.READ_BACK)
        }
    }

    private sealed interface BundleRead {
        data class Verified(val bundle: VerifiedReleaseCertificationBundle) : BundleRead
        data class Rejected(val result: InstallReleaseCertificationResult) : BundleRead
    }

    private sealed interface CertificationPreparation {
        data class Ready(
            val bundle: VerifiedReleaseCertificationBundle,
            val target: CertifiedTargetReceipt,
            val expected: PixelCameraProfile,
            val existing: PixelCameraProfile,
        ) : CertificationPreparation

        data class Rejected(val result: InstallReleaseCertificationResult) : CertificationPreparation
    }
}

private suspend fun <T> captureCertificationPersistence(
    block: suspend () -> T,
): CertificationPersistence<T> = try {
    CertificationPersistence.Succeeded(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    CertificationPersistence.Failed
}

private sealed interface CertificationPersistence<out T> {
    data class Succeeded<T>(val value: T) : CertificationPersistence<T>
    data object Failed : CertificationPersistence<Nothing>
}

private fun ReleaseCertificationReceipt.targetFor(codename: String): CertifiedTargetReceipt? = when (codename) {
    "panther" -> pixel7
    "husky" -> pixel8Pro
    else -> null
}

private object CertificationProfilePolicy {
    fun hasCertification(
        profile: PixelCameraProfile,
        bundle: VerifiedReleaseCertificationBundle,
    ): Boolean {
        val receipt = profile.certification ?: return false
        val exactReceipt = receipt.bundleSha256 == bundle.bundleSha256 &&
            receipt.lenswakeApkSha256 == bundle.installedApkSha256
        return profile.supportTier == SupportTier.CERTIFIED && exactReceipt
    }

    fun matchesAcceptedExperimentalProfile(
        profile: PixelCameraProfile,
        target: CertifiedTargetReceipt,
        expected: PixelCameraProfile,
    ): Boolean {
        val exactEvidence = profile.definitionFingerprint() == target.acceptedExperimentalProfileFingerprint
        val unclaimed = profile.supportTier == SupportTier.EXPERIMENTAL && profile.certification == null
        return unclaimed && exactEvidence && profile.matchesCatalogDefinition(expected)
    }

    fun certifiedBy(
        profile: PixelCameraProfile,
        bundle: VerifiedReleaseCertificationBundle,
    ): PixelCameraProfile = profile.copy(
        supportTier = SupportTier.CERTIFIED,
        certification = ProfileCertification(
            releaseTag = bundle.receipt.releaseTag,
            releaseCommit = bundle.receipt.releaseCommit,
            candidateRunId = bundle.receipt.candidateRunId,
            lenswakeApkSha256 = bundle.installedApkSha256,
            bundleSha256 = bundle.bundleSha256,
            pixel7EvidenceSha256 = bundle.receipt.pixel7.evidenceSha256,
            pixel8ProEvidenceSha256 = bundle.receipt.pixel8Pro.evidenceSha256,
        ),
    )

    private fun PixelCameraProfile.matchesCatalogDefinition(expected: PixelCameraProfile): Boolean =
        copy(
            compatibility = expected.compatibility,
            verifiedAt = expected.verifiedAt,
        ) == expected
}

/** Makes a persisted certification effective only while its exact signed APK remains installed. */
class ArtifactBoundAutomationProfileRepository(
    private val delegate: AutomationProfileRepository,
    private val installedReleaseApkSha256: () -> String?,
) : AutomationProfileRepository {
    override fun observeProfiles(): Flow<List<PixelCameraProfile>> =
        delegate.observeProfiles().map { profiles -> profiles.map(::effective) }

    override fun observePersistenceIssues() = delegate.observePersistenceIssues()

    override suspend fun get(id: ProfileId): PixelCameraProfile? = delegate.get(id)?.let(::effective)

    override suspend fun save(profile: PixelCameraProfile) {
        require(profile == effective(profile)) {
            "Certified profile does not match the installed signed release APK"
        }
        delegate.save(profile)
    }

    override suspend fun delete(id: ProfileId) = delegate.delete(id)

    private fun effective(profile: PixelCameraProfile): PixelCameraProfile {
        if (profile.supportTier != SupportTier.CERTIFIED) return profile
        val certification = profile.certification
        return if (
            certification != null &&
            certification.lenswakeApkSha256 == installedReleaseApkSha256()
        ) {
            profile
        } else {
            profile.copy(
                supportTier = SupportTier.EXPERIMENTAL,
                certification = null,
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
    }
}
