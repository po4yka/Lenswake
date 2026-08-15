package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.SupportTier
import kotlinx.coroutines.CancellationException

sealed interface InstallKnownPixelCameraProfileResult {
    data class Installed(
        val profile: PixelCameraProfile,
        val replacedExisting: Boolean,
    ) : InstallKnownPixelCameraProfileResult

    data class AlreadyInstalled(
        val profile: PixelCameraProfile,
    ) : InstallKnownPixelCameraProfileResult

    data class UnsupportedEnvironment(
        val environment: PixelCameraEnvironment,
    ) : InstallKnownPixelCameraProfileResult

    data class ExperimentalConsentRequired(
        val profile: PixelCameraProfile,
    ) : InstallKnownPixelCameraProfileResult

    data class EnvironmentUnavailable(
        val failure: AutomationFailure,
    ) : InstallKnownPixelCameraProfileResult

    data class PersistenceFailure(
        val stage: ProfilePersistenceStage,
        val detail: String,
    ) : InstallKnownPixelCameraProfileResult {
        init {
            require(detail.isNotBlank()) { "Persistence failure detail must not be blank" }
        }
    }
}

enum class ProfilePersistenceStage {
    READ_EXISTING,
    SAVE,
    READ_BACK,
}

/** Installs a fail-closed catalog profile only after an exact runtime-environment match. */
class InstallKnownPixelCameraProfile(
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val profileRepository: AutomationProfileRepository,
) {
    suspend operator fun invoke(
        experimentalRiskAccepted: Boolean = false,
    ): InstallKnownPixelCameraProfileResult =
        when (val observation = inspectEnvironment()) {
            is PortResult.Observed -> installFor(observation.value, experimentalRiskAccepted)
            is PortResult.Unavailable -> InstallKnownPixelCameraProfileResult.EnvironmentUnavailable(
                observation.failure,
            )
        }

    private suspend fun installFor(
        environment: PixelCameraEnvironment,
        experimentalRiskAccepted: Boolean,
    ): InstallKnownPixelCameraProfileResult {
        val catalogProfile = KnownPixelCameraProfileCatalog.exactMatch(environment)
        return if (catalogProfile == null) {
            InstallKnownPixelCameraProfileResult.UnsupportedEnvironment(environment)
        } else if (
            catalogProfile.supportTier == SupportTier.EXPERIMENTAL && !experimentalRiskAccepted
        ) {
            InstallKnownPixelCameraProfileResult.ExperimentalConsentRequired(catalogProfile)
        } else {
            install(catalogProfile)
        }
    }

    private suspend fun install(
        catalogProfile: PixelCameraProfile,
    ): InstallKnownPixelCameraProfileResult = observeOperation {
        profileRepository.get(catalogProfile.id)
    }.fold(
        onSuccess = { existing ->
            if (existing != null && KnownPixelCameraProfileCatalog.containsDefinition(existing)) {
                InstallKnownPixelCameraProfileResult.AlreadyInstalled(existing)
            } else {
                saveAndVerify(catalogProfile, replacedExisting = existing != null)
            }
        },
        onFailure = { failure ->
            persistenceFailure(ProfilePersistenceStage.READ_EXISTING, failure)
        },
    )

    private suspend fun saveAndVerify(
        catalogProfile: PixelCameraProfile,
        replacedExisting: Boolean,
    ): InstallKnownPixelCameraProfileResult = observeOperation {
        profileRepository.save(catalogProfile)
    }.fold(
        onSuccess = { verifyPersistedProfile(catalogProfile, replacedExisting) },
        onFailure = { failure -> persistenceFailure(ProfilePersistenceStage.SAVE, failure) },
    )

    private suspend fun verifyPersistedProfile(
        catalogProfile: PixelCameraProfile,
        replacedExisting: Boolean,
    ): InstallKnownPixelCameraProfileResult = observeOperation {
        profileRepository.get(catalogProfile.id)
    }.fold(
        onSuccess = { persisted ->
            if (persisted == catalogProfile) {
                InstallKnownPixelCameraProfileResult.Installed(
                    profile = persisted,
                    replacedExisting = replacedExisting,
                )
            } else {
                InstallKnownPixelCameraProfileResult.PersistenceFailure(
                    stage = ProfilePersistenceStage.READ_BACK,
                    detail = "The persisted profile did not match the catalog profile after save",
                )
            }
        },
        onFailure = { failure -> persistenceFailure(ProfilePersistenceStage.READ_BACK, failure) },
    )

    private suspend fun <T> observeOperation(operation: suspend () -> T): Result<T> {
        val result = runCatching { operation() }
        result.exceptionOrNull()?.rethrowNonRecoverable()
        return result
    }

    private fun inspectEnvironment(): PortResult<PixelCameraEnvironment> {
        val result = runCatching(environmentProbe)
        result.exceptionOrNull()?.rethrowNonRecoverable()
        return result.getOrElse { failure ->
            PortResult.Unavailable(
                AutomationFailure(
                    code = AutomationFailureCode.UNKNOWN,
                    message = "Pixel Camera environment inspection failed",
                    context = mapOf(
                        "exception" to failure::class.java.simpleName.take(MAX_EXCEPTION_TYPE_NAME_LENGTH),
                    ),
                ),
            )
        }
    }

    private fun Throwable.rethrowNonRecoverable() {
        if (this is CancellationException || this is Error) throw this
    }

    private companion object {
        const val MAX_EXCEPTION_TYPE_NAME_LENGTH = 256
    }

    private fun persistenceFailure(
        stage: ProfilePersistenceStage,
        failure: Throwable,
    ): InstallKnownPixelCameraProfileResult.PersistenceFailure =
        InstallKnownPixelCameraProfileResult.PersistenceFailure(
            stage = stage,
            detail = failure::class.java.simpleName.ifBlank { "Persistence operation failed" },
        )
}
