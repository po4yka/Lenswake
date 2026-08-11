package dev.po4yka.lenswake.core

import java.time.Instant

object PixelCameraSelectorSchema {
    const val CURRENT_VERSION: Int = 4
}

enum class PixelCameraDialogKind {
    VIDEO_DURATION_LIMIT_REACHED,
    VIDEO_FILE_SIZE_LIMIT_REACHED,
    VIDEO_STORAGE_EXHAUSTED,
    CAMERA_DISABLED,
    UNKNOWN,
}

data class PixelCameraDialogProfile(
    val presence: UiSelectorSet,
    val recoveryTarget: UiSelectorSet?,
)

data class PixelCameraEnvironment(
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidSdk: Int,
    val androidBuildFingerprint: String?,
    val cameraPackage: String,
    val cameraVersionCode: Long,
    val localeTag: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
) {
    init {
        require(deviceManufacturer.isNotBlank()) { "Device manufacturer must not be blank" }
        require(deviceModel.isNotBlank()) { "Device model must not be blank" }
        require(androidSdk > 0) { "Android SDK must be positive" }
        require(cameraPackage.isNotBlank()) { "Camera package must not be blank" }
        require(cameraVersionCode >= 0) { "Camera version code must not be negative" }
        require(localeTag.isNotBlank()) { "Locale tag must not be blank" }
        require(displayWidthPx > 0 && displayHeightPx > 0) { "Display dimensions must be positive" }
        require(densityDpi > 0) { "Display density must be positive" }
    }
}

enum class ProfileCompatibility {
    VERIFIED,
    PROBABLY_COMPATIBLE,
    NEEDS_REHEARSAL,
    INCOMPATIBLE,
}

object ProfileCompatibilityEvaluator {
    fun evaluate(
        calibrated: PixelCameraEnvironment,
        current: PixelCameraEnvironment,
    ): ProfileCompatibility {
        if (
            calibrated.deviceManufacturer != current.deviceManufacturer ||
            calibrated.deviceModel != current.deviceModel ||
            calibrated.cameraPackage != current.cameraPackage
        ) {
            return ProfileCompatibility.INCOMPATIBLE
        }

        if (calibrated.hasRuntimeConfigurationDrift(current)) {
            return ProfileCompatibility.NEEDS_REHEARSAL
        }

        return if (calibrated.androidBuildFingerprint == current.androidBuildFingerprint) {
            ProfileCompatibility.VERIFIED
        } else {
            ProfileCompatibility.PROBABLY_COMPATIBLE
        }
    }
}

private fun PixelCameraEnvironment.hasRuntimeConfigurationDrift(current: PixelCameraEnvironment): Boolean =
    listOf(
        androidSdk != current.androidSdk,
        cameraVersionCode != current.cameraVersionCode,
        localeTag != current.localeTag,
        displayWidthPx != current.displayWidthPx,
        displayHeightPx != current.displayHeightPx,
        densityDpi != current.densityDpi,
    ).any { it }

data class PixelCameraProfile(
    val id: ProfileId,
    val environment: PixelCameraEnvironment,
    val selectorSchemaVersion: Int,
    val targets: Map<AutomationAction, UiSelectorSet> = emptyMap(),
    val speedTargets: Map<TimeLapseSpeed, UiSelectorSet> = emptyMap(),
    val stateSignals: Map<PixelCameraStateSignal, UiSelectorSet> = emptyMap(),
    val dialogProfiles: Map<PixelCameraDialogKind, PixelCameraDialogProfile> = emptyMap(),
    val fallbackGestures: Map<AutomationAction, GestureProfile> = emptyMap(),
    val compatibility: ProfileCompatibility,
    val verifiedAt: Instant?,
) {
    init {
        require(selectorSchemaVersion > 0) { "Selector schema version must be positive" }
        require(compatibility != ProfileCompatibility.VERIFIED || verifiedAt != null) {
            "A verified profile requires a verification timestamp"
        }
        require(
            (
                targets.values +
                    speedTargets.values +
                    stateSignals.values +
                    dialogProfiles.values.map(PixelCameraDialogProfile::presence) +
                    dialogProfiles.values.mapNotNull(PixelCameraDialogProfile::recoveryTarget)
                )
                .flatMap(UiSelectorSet::selectors)
                .all { it.packageName == environment.cameraPackage },
        ) {
            "Profile selectors must be scoped to the calibrated camera package"
        }
        require(
            (
                targets.values +
                    speedTargets.values +
                    dialogProfiles.values.mapNotNull(PixelCameraDialogProfile::recoveryTarget)
                )
                .flatMap(UiSelectorSet::selectors)
                .all { it.hasMeaningfulDiscriminant },
        ) {
            "Action selectors require a resource, description, text, role, or region discriminant"
        }
        require(dialogProfiles[PixelCameraDialogKind.UNKNOWN]?.recoveryTarget == null) {
            "An unknown Pixel Camera dialog must never have an automatic recovery target"
        }
    }

    fun compatibilityFor(currentEnvironment: PixelCameraEnvironment): ProfileCompatibility {
        if (selectorSchemaVersion != PixelCameraSelectorSchema.CURRENT_VERSION) {
            return ProfileCompatibility.INCOMPATIBLE
        }
        val environmentCompatibility = ProfileCompatibilityEvaluator.evaluate(environment, currentEnvironment)
        return if (compatibility.ordinal >= environmentCompatibility.ordinal) {
            compatibility
        } else {
            environmentCompatibility
        }
    }
}

fun PixelCameraProfile.supportedCaptureConfigurations(): Set<CaptureConfiguration> = buildSet {
    LensSelection.entries.forEach { lens ->
        CaptureConfiguration.Video(lens).takeIf(::supports)?.let(::add)
        CaptureConfiguration.NightSightTimeLapse(lens).takeIf(::supports)?.let(::add)
        TimeLapseSpeed.entries.forEach { speed ->
            CaptureConfiguration.TimeLapse(speed, lens).takeIf(::supports)?.let(::add)
        }
    }
}

fun PixelCameraProfile.supports(capture: CaptureConfiguration): Boolean {
    if (capture.zoom != null) return false
    val requirements = capture.pixelCameraRequirements
    return requirements.actions.all(::hasAction) &&
        stateSignals.keys.containsAll(requirements.signals) &&
        (requirements.speedTarget == null || requirements.speedTarget in speedTargets)
}

private fun PixelCameraProfile.hasAction(action: AutomationAction): Boolean =
    action in targets || action in fallbackGestures

enum class AutomationAction {
    SELECT_VIDEO,
    SELECT_TIME_LAPSE,
    SELECT_NIGHT_SIGHT_TIME_LAPSE,
    OPEN_TIME_LAPSE_SPEED_CONTROL,
    SELECT_TIME_LAPSE_SPEED,
    SELECT_REAR_MAIN_LENS,
    SELECT_REAR_ULTRAWIDE_LENS,
    SELECT_REAR_TELEPHOTO_LENS,
    SELECT_FRONT_LENS,
    START_RECORDING,
    STOP_RECORDING,
    START_VIDEO_RECORDING,
    STOP_VIDEO_RECORDING,
    START_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
    STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
}

enum class PixelCameraStateSignal {
    PHOTO_MODE_ACTIVE,
    VIDEO_MODE_ACTIVE,
    TIME_LAPSE_MODE_ACTIVE,
    NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
    TIME_LAPSE_SPEED_AUTO_ACTIVE,
    TIME_LAPSE_SPEED_X5_ACTIVE,
    TIME_LAPSE_SPEED_X10_ACTIVE,
    TIME_LAPSE_SPEED_X30_ACTIVE,
    TIME_LAPSE_SPEED_X120_ACTIVE,
    TIME_LAPSE_SPEED_PICKER_OPEN,
    REAR_MAIN_LENS_ACTIVE,
    REAR_ULTRAWIDE_LENS_ACTIVE,
    REAR_TELEPHOTO_LENS_ACTIVE,
    FRONT_LENS_ACTIVE,
    RECORDING_ACTIVE,
    NOT_RECORDING,
}

data class UiSelectorSet(
    val selectors: List<UiSelector>,
    val minimumScore: Int,
) {
    init {
        require(selectors.isNotEmpty()) { "Selector set must not be empty" }
        require(minimumScore > 0) { "Minimum selector score must be positive" }
    }
}

data class UiSelector(
    val packageName: String,
    val resourceId: String? = null,
    val role: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val expectedSelected: Boolean? = null,
    val expectedChecked: Boolean? = null,
    val expectedRegion: NormalizedBounds? = null,
    val requiresClickable: Boolean = true,
    val requiresVisible: Boolean = true,
) {
    init {
        require(packageName.isNotBlank()) { "Selector package name must not be blank" }
    }

    val hasMeaningfulDiscriminant: Boolean
        get() =
            !resourceId.isNullOrBlank() ||
                !contentDescription.isNullOrBlank() ||
                !text.isNullOrBlank() ||
                !role.isNullOrBlank() ||
                expectedRegion != null
}

data class GestureProfile(
    val point: NormalizedPoint,
)

data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && x in 0f..1f) { "Normalized x must be between zero and one" }
        require(y.isFinite() && y in 0f..1f) { "Normalized y must be between zero and one" }
    }
}

data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "Normalized bounds must be between zero and one"
        }
        require(left < right && top < bottom) { "Normalized bounds must have positive area" }
    }
}
