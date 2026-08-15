package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.PixelCameraDialogProfile
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileSource
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY

/**
 * Version-pinned semantic selector candidates documented in
 * `docs/research/pixel-6-10a-template-provenance.md`.
 *
 * The standard definition comes from an explicitly authorized live Pixel 7 beta calibration; the
 * telephoto definition remains version-pinned static APK evidence. Neither is physical
 * certification. A catalog entry is offered only for an admitted exact stable environment, and
 * production use still requires a successful rehearsal of the exact capture configuration there.
 */
object KnownPixelCameraProfileCatalog {
    private val ACTIVE_MODE_REGION = NormalizedBounds(0.35f, 0.80f, 0.65f, 0.90f)

    val pixel8ProAndroid17Camera69481630: PixelCameraProfile = PixelCameraProfile(
        id = ProfileId(
            "google-pixel-8-pro-sdk37-cp2a-260705-006-camera-69481630-1008x2244-en-us-v5",
        ),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            deviceCodename = "husky",
            androidSdk = 37,
            androidBuildFingerprint =
                "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
            cameraPackage = PIXEL_CAMERA_PACKAGE,
            cameraVersionCode = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode,
            cameraSigningCertificateSha256 =
                SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
            localeTag = "en-US-u-fw-mon-mu-celsius",
            displayWidthPx = 1_008,
            displayHeightPx = 2_244,
            densityDpi = 360,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        supportTier = SupportTier.EXPERIMENTAL,
        source = ProfileSource.STATIC_RESOURCE_TEMPLATE,
        selectorTemplate = PixelCameraTemplateKind.SEMANTIC_TELEPHOTO.reference,
        targets = mapOf(
            AutomationAction.SELECT_VIDEO to actionSelector(
                resourceId = "video_supermode",
                minimumScore = 110,
            ),
            AutomationAction.SELECT_VIDEO_RESOLUTION_4K to actionSelector(
                contentDescription = "4K Ultra HD",
                text = "4K (Ultra HD)",
                minimumScore = 90,
            ),
            AutomationAction.SELECT_VIDEO_FRAME_RATE_60 to actionSelector(
                contentDescription = "60 FPS",
                text = "60",
                minimumScore = 90,
            ),
            AutomationAction.SELECT_TIME_LAPSE to UiSelectorSet(
                selectors = listOf(
                    cameraSelector(
                        resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                        contentDescription = "Switch to Time Lapse Mode",
                        text = "Time Lapse",
                        requiresClickable = false,
                    ),
                    cameraSelector(
                        resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                        contentDescription = "Time Lapse",
                        text = "Time Lapse",
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 190,
            ),
            AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL to actionSelector(
                contentDescription = "Time Lapse control",
                minimumScore = 60,
                requiresClickable = false,
            ),
            AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE to actionSelector(
                text = "Night Sight",
                minimumScore = 30,
            ),
            AutomationAction.SELECT_REAR_ULTRAWIDE_LENS to actionSelector(
                contentDescription = "Ultrawide",
                minimumScore = 60,
                requiresClickable = false,
            ),
            AutomationAction.SELECT_REAR_TELEPHOTO_LENS to actionSelector(
                contentDescription = "Tele",
                minimumScore = 60,
                requiresClickable = false,
            ),
            AutomationAction.SELECT_FRONT_LENS to actionSelector(
                contentDescription = "Switch to front camera",
                minimumScore = 60,
            ),
            AutomationAction.SELECT_REAR_MAIN_LENS to actionSelector(
                resourceId = "zoom_toggle_1×",
                text = "1×",
                minimumScore = 130,
                requiresClickable = false,
            ),
            AutomationAction.START_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Start time lapse",
                minimumScore = 170,
            ),
            AutomationAction.STOP_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Stop time lapse",
                minimumScore = 170,
            ),
            AutomationAction.START_VIDEO_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Start video",
                minimumScore = 170,
            ),
            AutomationAction.STOP_VIDEO_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Stop video",
                minimumScore = 170,
            ),
            AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Start time lapse",
                minimumScore = 170,
            ),
            AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Stop time lapse",
                minimumScore = 170,
            ),
        ),
        speedTargets = mapOf(
            TimeLapseSpeed.AUTO to actionSelector(
                contentDescription = "Time Lapse auto speed",
                text = "Auto",
                minimumScore = 90,
            ),
            TimeLapseSpeed.X5 to timeLapseSpeedSelector(5),
            TimeLapseSpeed.X10 to timeLapseSpeedSelector(10),
            TimeLapseSpeed.X30 to timeLapseSpeedSelector(30),
            TimeLapseSpeed.X120 to actionSelector(
                contentDescription = "Time Lapse 120 times speed",
                text = "120×",
                minimumScore = 100,
            ),
        ),
        dialogProfiles = mapOf(
            PixelCameraDialogKind.VIDEO_DURATION_LIMIT_REACHED to PixelCameraDialogProfile(
                presence = dialogPresence("Video reached the duration limit."),
                recoveryTarget = dialogAction("OK"),
            ),
            PixelCameraDialogKind.VIDEO_FILE_SIZE_LIMIT_REACHED to PixelCameraDialogProfile(
                presence = dialogPresence("Video reached the 100 GB size limit."),
                recoveryTarget = dialogAction("OK"),
            ),
            PixelCameraDialogKind.VIDEO_STORAGE_EXHAUSTED to PixelCameraDialogProfile(
                presence = dialogPresence(
                    "There is not enough storage available to continue capturing. " +
                        "You can free up space in the Files app.",
                ),
                recoveryTarget = null,
            ),
            PixelCameraDialogKind.CAMERA_DISABLED to PixelCameraDialogProfile(
                presence = dialogPresence(
                    "Your organization doesn't allow you to use Camera. " +
                        "Contact your IT admin for more info.",
                ),
                recoveryTarget = null,
            ),
            PixelCameraDialogKind.UNKNOWN to PixelCameraDialogProfile(
                presence = dialogPresence(text = null),
                recoveryTarget = null,
            ),
        ),
        stateSignals = mapOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                text = "Photo",
                expectedSelected = true,
                expectedRegion = ACTIVE_MODE_REGION,
                minimumScore = 155,
            ),
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                text = "Video",
                expectedSelected = true,
                expectedRegion = ACTIVE_MODE_REGION,
                minimumScore = 155,
            ),
            PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE to stateSelector(
                contentDescription = "4K Ultra HD",
                text = "4K (Ultra HD)",
                expectedChecked = true,
                minimumScore = 105,
            ),
            PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE to stateSelector(
                contentDescription = "60 FPS",
                text = "60",
                expectedChecked = true,
                minimumScore = 105,
            ),
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                contentDescription = "Time Lapse",
                text = "Time Lapse",
                expectedSelected = true,
                expectedRegion = ACTIVE_MODE_REGION,
                minimumScore = 215,
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = PIXEL_CAMERA_PACKAGE,
                        contentDescription = "Time Lapse 120 times speed",
                        text = "120×",
                        expectedSelected = true,
                        requiresClickable = false,
                    ),
                    UiSelector(
                        packageName = PIXEL_CAMERA_PACKAGE,
                        text = "120×",
                        expectedRegion = NormalizedBounds(0.65f, 0.80f, 1f, 1f),
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 40,
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_AUTO_ACTIVE to speedStateSelector(
                "Time Lapse auto speed",
                "Auto",
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X5_ACTIVE to speedStateSelector(
                "Time Lapse 5 times speed",
                "5×",
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X10_ACTIVE to speedStateSelector(
                "Time Lapse 10 times speed",
                "10×",
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE to speedStateSelector(
                "Time Lapse 30 times speed",
                "30×",
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN to UiSelectorSet(
                selectors = listOf(
                    cameraSelector(contentDescription = "Time Lapse auto speed", requiresClickable = false),
                    cameraSelector(contentDescription = "Time Lapse 5 times speed", requiresClickable = false),
                    cameraSelector(contentDescription = "Time Lapse 10 times speed", requiresClickable = false),
                    cameraSelector(contentDescription = "Time Lapse 30 times speed", requiresClickable = false),
                    cameraSelector(contentDescription = "Time Lapse 120 times speed", requiresClickable = false),
                ),
                minimumScore = 60,
            ),
            PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE to stateSelector(
                text = "Night Sight auto enabled. Learn more",
                minimumScore = 30,
            ),
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE to stateSelector(
                expectedChecked = true,
                expectedRegion = NormalizedBounds(0.40f, 0.60f, 0.50f, 0.68f),
                minimumScore = 35,
                requiresClickable = true,
            ),
            PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE to lensStateSelector("Ultrawide"),
            PixelCameraStateSignal.REAR_TELEPHOTO_LENS_ACTIVE to lensStateSelector("Tele"),
            PixelCameraStateSignal.FRONT_LENS_ACTIVE to stateSelector(
                contentDescription = "Switch to back camera",
                minimumScore = 60,
            ),
            PixelCameraStateSignal.RECORDING_ACTIVE to UiSelectorSet(
                selectors = listOf("Stop video", "Stop time lapse").map { description ->
                    cameraSelector(
                        resourceId = "ComposeShutter",
                        contentDescription = description,
                        requiresClickable = false,
                    )
                },
                minimumScore = 160,
            ),
            PixelCameraStateSignal.NOT_RECORDING to UiSelectorSet(
                selectors = listOf(
                    cameraSelector(
                        resourceId = "ComposeShutter",
                        contentDescription = "Take photo",
                    ),
                    cameraSelector(
                        resourceId = "ComposeShutter",
                        contentDescription = "Start video",
                    ),
                    cameraSelector(
                        resourceId = "ComposeShutter",
                        contentDescription = "Start time lapse",
                    ),
                ),
                minimumScore = 160,
            ),
        ),
        compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
        verifiedAt = null,
    )

    internal val pixel7SemanticTemplate: PixelCameraProfile = Pixel7SemanticTemplate.profile

    fun exactMatch(environment: PixelCameraEnvironment): PixelCameraProfile? {
        val model = SupportedPixelModelRegistry.find(
            environment.deviceManufacturer,
            environment.deviceModel,
            environment.deviceCodename,
        ) ?: return null
        if (!PixelCameraProfileTemplateFactory.isSupportedRuntime(model, environment)) return null
        if (environment == pixel8ProAndroid17Camera69481630.environment) {
            return pixel8ProAndroid17Camera69481630
        }
        val template = when (model.template) {
            PixelCameraTemplateKind.SEMANTIC_STANDARD -> pixel7SemanticTemplate
            PixelCameraTemplateKind.SEMANTIC_TELEPHOTO -> pixel8ProAndroid17Camera69481630
        }
        return PixelCameraProfileTemplateFactory.derivedProfile(
            model,
            environment,
            template,
        )
    }

    /** True when [profile] has the current catalog definition, allowing runtime evidence to differ. */
    fun containsDefinition(profile: PixelCameraProfile): Boolean {
        val known = exactMatch(profile.environment)?.takeIf { it.id == profile.id } ?: return false
        val installableStatuses = setOf(
            ProfileCompatibility.NEEDS_REHEARSAL,
            ProfileCompatibility.VERIFIED,
        )
        if (profile.compatibility !in installableStatuses) {
            return false
        }
        return profile.copy(
            supportTier = known.supportTier,
            certification = known.certification,
            compatibility = known.compatibility,
            verifiedAt = known.verifiedAt,
        ) == known
    }

    private fun timeLapseSpeedSelector(speed: Int): UiSelectorSet = actionSelector(
        contentDescription = "Time Lapse $speed times speed",
        text = "$speed×",
        minimumScore = 90,
    )

    private fun speedStateSelector(
        description: String,
        text: String,
    ): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            cameraSelector(
                contentDescription = description,
                text = text,
                expectedSelected = true,
                requiresClickable = false,
            ),
        ),
        minimumScore = 105,
    )

    private fun lensStateSelector(description: String): UiSelectorSet = stateSelector(
        contentDescription = description,
        expectedChecked = true,
        minimumScore = 75,
        requiresClickable = false,
    )

    private fun actionSelector(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        minimumScore: Int,
        requiresClickable: Boolean = true,
    ): UiSelectorSet = selectorSet(
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        minimumScore = minimumScore,
        requiresClickable = requiresClickable,
    )

    private fun stateSelector(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        expectedSelected: Boolean? = null,
        expectedChecked: Boolean? = null,
        expectedRegion: NormalizedBounds? = null,
        minimumScore: Int,
        requiresClickable: Boolean = false,
    ): UiSelectorSet = selectorSet(
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        expectedSelected = expectedSelected,
        expectedChecked = expectedChecked,
        expectedRegion = expectedRegion,
        minimumScore = minimumScore,
        requiresClickable = requiresClickable,
    )

    private fun dialogPresence(text: String?): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            cameraSelector(
                resourceId = "android:id/message",
                text = text,
                role = "android.widget.TextView",
                requiresClickable = false,
            ),
        ),
        minimumScore = 150,
    )

    private fun dialogAction(text: String): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            cameraSelector(
                resourceId = "android:id/button1",
                text = text,
                role = "android.widget.Button",
                requiresClickable = true,
            ),
        ),
        minimumScore = 160,
    )

    private fun selectorSet(
        resourceId: String?,
        contentDescription: String?,
        text: String?,
        expectedSelected: Boolean? = null,
        expectedChecked: Boolean? = null,
        expectedRegion: NormalizedBounds? = null,
        minimumScore: Int,
        requiresClickable: Boolean,
    ): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            cameraSelector(
                resourceId = resourceId,
                contentDescription = contentDescription,
                text = text,
                expectedSelected = expectedSelected,
                expectedChecked = expectedChecked,
                expectedRegion = expectedRegion,
                requiresClickable = requiresClickable,
            ),
        ),
        minimumScore = minimumScore,
    )

    private fun cameraSelector(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        role: String? = null,
        expectedSelected: Boolean? = null,
        expectedChecked: Boolean? = null,
        expectedRegion: NormalizedBounds? = null,
        requiresClickable: Boolean = true,
    ): UiSelector = UiSelector(
        packageName = PIXEL_CAMERA_PACKAGE,
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        role = role,
        expectedSelected = expectedSelected,
        expectedChecked = expectedChecked,
        expectedRegion = expectedRegion,
        requiresClickable = requiresClickable,
    )

}

internal fun isSupportedPixelCameraRuntime(environment: PixelCameraEnvironment): Boolean =
    SupportedPixelModelRegistry.find(
        environment.deviceManufacturer,
        environment.deviceModel,
        environment.deviceCodename,
    )?.let { model -> PixelCameraProfileTemplateFactory.isSupportedRuntime(model, environment) } == true
