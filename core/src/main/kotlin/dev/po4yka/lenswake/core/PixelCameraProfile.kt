package dev.po4yka.lenswake.core

import java.time.Instant

const val LEGACY_UNKNOWN_FONT_SCALE: Float = -1f

object PixelCameraSelectorSchema {
    const val CURRENT_VERSION: Int = 5
}

enum class SupportTier {
    CERTIFIED,
    EXPERIMENTAL,
}

/** Immutable evidence binding a certified profile to one physically accepted release artifact. */
data class ProfileCertification(
    val releaseTag: String,
    val releaseCommit: String,
    val candidateRunId: Long,
    val lenswakeApkSha256: String,
    val bundleSha256: String,
    val pixel7EvidenceSha256: String,
    val pixel8ProEvidenceSha256: String,
) {
    init {
        require(RELEASE_TAG.matches(releaseTag)) { "Certification release tag must be v<SemVer>" }
        require(GIT_COMMIT.matches(releaseCommit)) { "Certification commit must be a full Git SHA" }
        require(candidateRunId > 0) { "Certification candidate run ID must be positive" }
        listOf(
            lenswakeApkSha256,
            bundleSha256,
            pixel7EvidenceSha256,
            pixel8ProEvidenceSha256,
        ).forEach { digest ->
            require(SHA_256.matches(digest)) {
                "Certification digests must be 64 lowercase hexadecimal characters"
            }
        }
        require(pixel7EvidenceSha256 != pixel8ProEvidenceSha256) {
            "Certified devices require distinct physical evidence records"
        }
    }

    private companion object {
        val RELEASE_TAG = Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$")
        val GIT_COMMIT = Regex("^[0-9a-f]{40}$")
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

enum class ProfileSource {
    PHYSICAL_TEMPLATE,
    STATIC_RESOURCE_TEMPLATE,
    EXACT_ENVIRONMENT_DERIVATION,
    LEGACY_UNKNOWN,
}

data class SelectorTemplateReference(
    val id: String,
    val version: Int,
) {
    init {
        require(id.isNotBlank()) { "Selector template id must not be blank" }
        require(version > 0) { "Selector template version must be positive" }
    }
}

data class ProfileProvenance(
    val supportTier: SupportTier,
    val source: ProfileSource,
    val selectorTemplate: SelectorTemplateReference,
)

val PixelCameraProfile.provenance: ProfileProvenance
    get() = ProfileProvenance(supportTier, source, selectorTemplate)

val LEGACY_PROFILE_PROVENANCE = ProfileProvenance(
    SupportTier.EXPERIMENTAL,
    ProfileSource.LEGACY_UNKNOWN,
    SelectorTemplateReference("legacy", 1),
)

enum class DisplayOrientation {
    PORTRAIT,
    LANDSCAPE,
    LEGACY_UNKNOWN,
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
    val deviceCodename: String = "legacy-unknown",
    val androidSdk: Int,
    val androidBuildFingerprint: String?,
    val cameraPackage: String,
    val cameraVersionCode: Long,
    val cameraSigningCertificateSha256: String = "legacy-unknown",
    val localeTag: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val fontScale: Float = 1f,
    val orientation: DisplayOrientation = DisplayOrientation.PORTRAIT,
    val defaultDisplayConfiguration: Boolean = true,
) {
    init {
        require(deviceManufacturer.isNotBlank()) { "Device manufacturer must not be blank" }
        require(deviceModel.isNotBlank()) { "Device model must not be blank" }
        require(deviceCodename.isNotBlank()) { "Device codename must not be blank" }
        require(androidSdk > 0) { "Android SDK must be positive" }
        require(cameraPackage.isNotBlank()) { "Camera package must not be blank" }
        require(cameraVersionCode >= 0) { "Camera version code must not be negative" }
        require(cameraSigningCertificateSha256.isNotBlank()) {
            "Camera signing certificate digest must not be blank"
        }
        require(localeTag.isNotBlank()) { "Locale tag must not be blank" }
        require(displayWidthPx > 0 && displayHeightPx > 0) { "Display dimensions must be positive" }
        require(densityDpi > 0) { "Display density must be positive" }
        require(fontScale == LEGACY_UNKNOWN_FONT_SCALE || fontScale.isFinite() && fontScale > 0f) {
            "Font scale must be positive and finite, or the legacy unknown sentinel"
        }
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
        if (!calibrated.sameDeviceFamily(current)) {
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

private fun PixelCameraEnvironment.sameDeviceFamily(current: PixelCameraEnvironment): Boolean =
    deviceManufacturer == current.deviceManufacturer &&
        deviceModel == current.deviceModel &&
        deviceCodename == current.deviceCodename &&
        cameraPackage == current.cameraPackage

private fun PixelCameraEnvironment.hasRuntimeConfigurationDrift(current: PixelCameraEnvironment): Boolean =
    listOf(
        androidSdk != current.androidSdk,
        cameraVersionCode != current.cameraVersionCode,
        cameraSigningCertificateSha256 != current.cameraSigningCertificateSha256,
        localeTag != current.localeTag,
        displayWidthPx != current.displayWidthPx,
        displayHeightPx != current.displayHeightPx,
        densityDpi != current.densityDpi,
        fontScale != current.fontScale,
        orientation != current.orientation,
        defaultDisplayConfiguration != current.defaultDisplayConfiguration,
    ).any { it }

data class PixelCameraProfile(
    val id: ProfileId,
    val environment: PixelCameraEnvironment,
    val selectorSchemaVersion: Int,
    val supportTier: SupportTier = SupportTier.EXPERIMENTAL,
    val certification: ProfileCertification? = null,
    val source: ProfileSource = ProfileSource.PHYSICAL_TEMPLATE,
    val selectorTemplate: SelectorTemplateReference = SelectorTemplateReference("legacy", 1),
    val videoSettings: VideoSettings = PIXEL_CAMERA_VIDEO_SETTINGS,
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
        require(supportTier != SupportTier.CERTIFIED || certification != null) {
            "A certified profile requires a release certification receipt"
        }
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
                    dialogProfiles.values.map(PixelCameraDialogProfile::presence) +
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
    if (
        capture is CaptureConfiguration.Video &&
        (videoSettings != PIXEL_CAMERA_VIDEO_SETTINGS || capture.videoSettings != videoSettings)
    ) {
        return false
    }
    val requirements = capture.pixelCameraRequirements
    return requirements.actions.all(::hasAction) &&
        stateSignals.keys.containsAll(requirements.signals) &&
        (requirements.speedTarget == null || requirements.speedTarget in speedTargets)
}

private fun PixelCameraProfile.hasAction(action: AutomationAction): Boolean =
    action in targets || action in fallbackGestures

enum class AutomationAction {
    SELECT_VIDEO,
    SELECT_VIDEO_RESOLUTION_4K,
    SELECT_VIDEO_FRAME_RATE_60,
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
    VIDEO_RESOLUTION_4K_ACTIVE,
    VIDEO_FRAME_RATE_60_ACTIVE,
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
