package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
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
    suspend operator fun invoke(): InstallKnownPixelCameraProfileResult {
        val environment = when (val observation = inspectEnvironment()) {
            is PortResult.Observed -> observation.value
            is PortResult.Unavailable -> return InstallKnownPixelCameraProfileResult.EnvironmentUnavailable(
                observation.failure,
            )
        }
        val catalogProfile = KnownPixelCameraProfileCatalog.exactMatch(environment)
            ?: return InstallKnownPixelCameraProfileResult.UnsupportedEnvironment(environment)

        val existing = try {
            profileRepository.get(catalogProfile.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return persistenceFailure(ProfilePersistenceStage.READ_EXISTING, failure)
        }
        if (existing != null && KnownPixelCameraProfileCatalog.containsDefinition(existing)) {
            return InstallKnownPixelCameraProfileResult.AlreadyInstalled(existing)
        }

        try {
            profileRepository.save(catalogProfile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return persistenceFailure(ProfilePersistenceStage.SAVE, failure)
        }

        val persisted = try {
            profileRepository.get(catalogProfile.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return persistenceFailure(ProfilePersistenceStage.READ_BACK, failure)
        }
        if (persisted != catalogProfile) {
            return InstallKnownPixelCameraProfileResult.PersistenceFailure(
                stage = ProfilePersistenceStage.READ_BACK,
                detail = "The persisted profile did not match the catalog profile after save",
            )
        }

        return InstallKnownPixelCameraProfileResult.Installed(
            profile = persisted,
            replacedExisting = existing != null,
        )
    }

    private fun inspectEnvironment(): PortResult<PixelCameraEnvironment> = try {
        environmentProbe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PortResult.Unavailable(
            AutomationFailure(
                code = AutomationFailureCode.UNKNOWN,
                message = "Pixel Camera environment inspection failed",
                context = mapOf("exception" to failure::class.java.simpleName.take(256)),
            ),
        )
    }

    private fun persistenceFailure(
        stage: ProfilePersistenceStage,
        failure: Exception,
    ): InstallKnownPixelCameraProfileResult.PersistenceFailure =
        InstallKnownPixelCameraProfileResult.PersistenceFailure(
            stage = stage,
            detail = failure::class.java.simpleName.ifBlank { "Persistence operation failed" },
        )
}
