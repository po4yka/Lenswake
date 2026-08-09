package dev.po4yka.lenswake.core

import java.time.Instant

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

        if (
            calibrated.androidSdk != current.androidSdk ||
            calibrated.cameraVersionCode != current.cameraVersionCode ||
            calibrated.localeTag != current.localeTag ||
            calibrated.displayWidthPx != current.displayWidthPx ||
            calibrated.displayHeightPx != current.displayHeightPx ||
            calibrated.densityDpi != current.densityDpi
        ) {
            return ProfileCompatibility.NEEDS_REHEARSAL
        }

        return if (calibrated.androidBuildFingerprint == current.androidBuildFingerprint) {
            ProfileCompatibility.VERIFIED
        } else {
            ProfileCompatibility.PROBABLY_COMPATIBLE
        }
    }
}

data class PixelCameraProfile(
    val id: ProfileId,
    val environment: PixelCameraEnvironment,
    val selectorSchemaVersion: Int,
    val targets: Map<AutomationAction, UiSelectorSet> = emptyMap(),
    val fallbackGestures: Map<AutomationAction, GestureProfile> = emptyMap(),
    val compatibility: ProfileCompatibility,
    val verifiedAt: Instant?,
) {
    init {
        require(selectorSchemaVersion > 0) { "Selector schema version must be positive" }
    }

    fun compatibilityFor(currentEnvironment: PixelCameraEnvironment): ProfileCompatibility {
        val environmentCompatibility = ProfileCompatibilityEvaluator.evaluate(environment, currentEnvironment)
        return if (compatibility.ordinal >= environmentCompatibility.ordinal) {
            compatibility
        } else {
            environmentCompatibility
        }
    }
}

enum class AutomationAction {
    SELECT_VIDEO,
    SELECT_TIME_LAPSE,
    SELECT_TIME_LAPSE_SPEED,
    START_RECORDING,
    STOP_RECORDING,
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
    val expectedRegion: NormalizedBounds? = null,
    val requiresClickable: Boolean = true,
    val requiresVisible: Boolean = true,
)

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
