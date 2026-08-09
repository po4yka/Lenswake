package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PixelCameraPort
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.platform.CameraLaunchDispatch
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE
import dev.po4yka.lenswake.platform.PlatformCapabilityCode
import dev.po4yka.lenswake.platform.SecurePixelCameraLauncher

/**
 * Production Android boundary for semantic Pixel Camera operations.
 *
 * It contains no Pixel Camera selectors. Both actions and observable state are entirely defined by
 * the persisted profile. Dispatch acceptance is returned separately from state verification.
 */
class PixelCameraAccessibilityPort internal constructor(
    private val cameraLauncher: () -> CameraLaunchDispatch,
    private val selectorMatcher: SelectorMatcher,
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val accessibilityGateway: PixelCameraAccessibilityGateway,
) : PixelCameraPort {
    constructor(
        launcher: SecurePixelCameraLauncher,
        selectorMatcher: SelectorMatcher,
        environmentProbe: AndroidPixelCameraEnvironmentProbe,
    ) : this(
        cameraLauncher = launcher::dispatch,
        selectorMatcher = selectorMatcher,
        environmentProbe = environmentProbe::inspect,
        accessibilityGateway = RuntimePixelCameraAccessibilityGateway,
    )

    override suspend fun inspect(profile: PixelCameraProfile): PortResult<PixelCameraState> {
        validateProfile(profile)?.let { return PortResult.Unavailable(it) }
        return when (val snapshot = accessibilityGateway.snapshot()) {
            AccessibilitySnapshotResult.ServiceDisconnected -> PortResult.Unavailable(
                failure(
                    AutomationFailureCode.ACCESSIBILITY_DISABLED,
                    "Lenswake Accessibility Service is not connected",
                ),
            )

            AccessibilitySnapshotResult.NoActiveWindow,
            AccessibilitySnapshotResult.PixelCameraNotForeground,
            -> PortResult.Observed(PixelCameraState.NotRunning)

            is AccessibilitySnapshotResult.Available -> {
                if (snapshot.truncated) {
                    PortResult.Unavailable(
                        failure(
                            AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                            "The bounded Pixel Camera accessibility snapshot was truncated",
                        ),
                    )
                } else {
                    inferState(profile, snapshot.nodes)
                }
            }
        }
    }

    override suspend fun launchSecureCamera(profile: PixelCameraProfile): ActionDispatch {
        validateProfile(profile)?.let { return ActionDispatch.Rejected(it) }
        return when (val result = cameraLauncher()) {
            is CameraLaunchDispatch.Dispatched -> ActionDispatch.Dispatched(
                InteractionMethod.STANDARD_ANDROID_API,
            )

            is CameraLaunchDispatch.Unavailable -> ActionDispatch.Rejected(
                AutomationFailure(
                    code = when (result.capability.code) {
                        PlatformCapabilityCode.PIXEL_CAMERA_NOT_INSTALLED ->
                            AutomationFailureCode.PIXEL_CAMERA_NOT_INSTALLED
                        PlatformCapabilityCode.SECURE_CAMERA_NOT_RESOLVABLE,
                        PlatformCapabilityCode.RESOLVED_ACTIVITY_WRONG_PACKAGE,
                        PlatformCapabilityCode.RESOLVED_ACTIVITY_NOT_EXPORTED,
                        -> AutomationFailureCode.PIXEL_CAMERA_RESOLUTION_FAILED
                        PlatformCapabilityCode.SECURE_CAMERA_DISPATCH_REJECTED,
                        PlatformCapabilityCode.SECURE_CAMERA_DISPATCH_FAILED,
                        PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
                        -> AutomationFailureCode.PIXEL_CAMERA_LAUNCH_FAILED
                    },
                    message = result.capability.detail,
                    context = mapOf("platformCapability" to result.capability.code.name),
                ),
            )
        }
    }

    override suspend fun selectVideo(profile: PixelCameraProfile): ActionDispatch =
        dispatch(AutomationAction.SELECT_VIDEO, profile)

    override suspend fun selectTimeLapse(profile: PixelCameraProfile): ActionDispatch =
        dispatch(AutomationAction.SELECT_TIME_LAPSE, profile)

    override suspend fun selectTimeLapseSpeed(
        speed: TimeLapseSpeed,
        profile: PixelCameraProfile,
    ): ActionDispatch = dispatch(AutomationAction.SELECT_TIME_LAPSE_SPEED, profile, speed)

    override suspend fun selectRearMainLens(profile: PixelCameraProfile): ActionDispatch =
        dispatch(AutomationAction.SELECT_REAR_MAIN_LENS, profile)

    override suspend fun startRecording(profile: PixelCameraProfile): ActionDispatch =
        dispatch(AutomationAction.START_RECORDING, profile)

    override suspend fun stopRecording(profile: PixelCameraProfile): ActionDispatch =
        dispatch(AutomationAction.STOP_RECORDING, profile)

    private suspend fun dispatch(
        action: AutomationAction,
        profile: PixelCameraProfile,
        speed: TimeLapseSpeed? = null,
    ): ActionDispatch {
        validateProfile(profile)?.let { return ActionDispatch.Rejected(it) }
        val snapshot = when (val result = accessibilityGateway.snapshot()) {
            is AccessibilitySnapshotResult.Available -> result
            AccessibilitySnapshotResult.ServiceDisconnected -> return ActionDispatch.Rejected(
                failure(
                    AutomationFailureCode.ACCESSIBILITY_DISABLED,
                    "Lenswake Accessibility Service is not connected",
                ),
            )
            AccessibilitySnapshotResult.NoActiveWindow,
            AccessibilitySnapshotResult.PixelCameraNotForeground,
            -> return ActionDispatch.Rejected(
                failure(
                    AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
                    "Pixel Camera is not the active accessibility window",
                ),
            )
        }
        if (snapshot.truncated) {
            return ActionDispatch.Rejected(
                failure(
                    AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "The bounded Pixel Camera accessibility snapshot was truncated",
                ),
            )
        }

        val match = if (action == AutomationAction.SELECT_TIME_LAPSE_SPEED) {
            val requestedSpeed = requireNotNull(speed) {
                "A Time Lapse speed is required for the speed-selection action"
            }
            val selectors = profile.speedTargets[requestedSpeed]
                ?: return ActionDispatch.Rejected(missingActionFailure(action))
            selectorMatcher.match(selectors, profile, snapshot.nodes)
        } else {
            selectorMatcher.match(action, profile, snapshot.nodes)
        }
        val nodePath = when (match) {
            is SelectorMatchResult.Match -> match.node.id
            is SelectorMatchResult.Ambiguous -> return ActionDispatch.Rejected(
                failure(
                    AutomationFailureCode.UI_TARGET_AMBIGUOUS,
                    "Multiple equally confident Pixel Camera targets matched $action",
                ),
            )
            is SelectorMatchResult.BelowThreshold -> return ActionDispatch.Rejected(
                AutomationFailure(
                    code = AutomationFailureCode.UI_TARGET_CONFIDENCE_TOO_LOW,
                    message = "Pixel Camera target confidence was below the profile threshold",
                    context = mapOf(
                        "action" to action.name,
                        "bestScore" to match.bestScore.toString(),
                        "minimumScore" to match.minimumScore.toString(),
                    ),
                ),
            )
            SelectorMatchResult.NoEligibleNodes,
            SelectorMatchResult.TargetNotConfigured,
            -> return ActionDispatch.Rejected(missingActionFailure(action))
        }

        return when (accessibilityGateway.dispatchClick(nodePath)) {
            AccessibilityDispatchResult.SemanticActionDispatched -> ActionDispatch.Dispatched(
                InteractionMethod.ACCESSIBILITY_ACTION,
            )
            AccessibilityDispatchResult.GestureSubmitted -> ActionDispatch.Dispatched(
                InteractionMethod.ACCESSIBILITY_NODE_GESTURE,
            )
            AccessibilityDispatchResult.ServiceDisconnected -> ActionDispatch.Rejected(
                failure(
                    AutomationFailureCode.ACCESSIBILITY_DISABLED,
                    "Lenswake Accessibility Service disconnected before dispatch",
                ),
            )
            AccessibilityDispatchResult.TargetNotFound,
            AccessibilityDispatchResult.TargetNotEligible,
            AccessibilityDispatchResult.GestureRejected,
            -> ActionDispatch.Rejected(missingActionFailure(action))
        }
    }

    private fun inferState(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): PortResult<PixelCameraState> {
        val requiredSignals = setOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
            PixelCameraStateSignal.RECORDING_ACTIVE,
            PixelCameraStateSignal.NOT_RECORDING,
        )
        val missingSignals = requiredSignals - profile.stateSignals.keys
        if (missingSignals.isNotEmpty()) {
            return PortResult.Unavailable(
                AutomationFailure(
                    code = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    message = "The profile lacks required observable Pixel Camera state signals",
                    context = mapOf("missingSignals" to missingSignals.sortedBy { it.name }.joinToString(",") { it.name }),
                ),
            )
        }

        val active = linkedSetOf<PixelCameraStateSignal>()
        for ((signal, selectorSet) in profile.stateSignals) {
            when (selectorMatcher.match(selectorSet, profile, nodes)) {
                is SelectorMatchResult.Match -> active += signal
                is SelectorMatchResult.Ambiguous -> return PortResult.Unavailable(
                    AutomationFailure(
                        code = AutomationFailureCode.UI_TARGET_AMBIGUOUS,
                        message = "The Pixel Camera state signal was ambiguous",
                        context = mapOf("signal" to signal.name),
                    ),
                )
                is SelectorMatchResult.BelowThreshold,
                SelectorMatchResult.NoEligibleNodes,
                SelectorMatchResult.TargetNotConfigured,
                -> Unit
            }
        }

        val recordingSignals = active.intersect(
            setOf(PixelCameraStateSignal.RECORDING_ACTIVE, PixelCameraStateSignal.NOT_RECORDING),
        )
        if (recordingSignals.size != 1) {
            return unavailableConflictingState("recording", recordingSignals)
        }
        val recording = PixelCameraStateSignal.RECORDING_ACTIVE in recordingSignals

        val modeSignals = active.intersect(
            setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            ),
        )
        if (modeSignals.size != 1) {
            return if (recording && modeSignals.isEmpty()) {
                PortResult.Observed(PixelCameraState.RecordingUnknownMode)
            } else {
                unavailableConflictingState("mode", modeSignals)
            }
        }

        return when (modeSignals.single()) {
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE -> if (recording) {
                PortResult.Observed(PixelCameraState.RecordingUnknownMode)
            } else {
                PortResult.Observed(PixelCameraState.Photo)
            }
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE -> PortResult.Observed(
                PixelCameraState.Video(recording),
            )
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE -> inferTimeLapse(active, recording)
            else -> error("Only mode signals are considered")
        }
    }

    private fun inferTimeLapse(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> {
        val activeSpeeds = SPEED_SIGNALS.filterKeys(active::contains).values.toSet()
        if (activeSpeeds.size > 1) return unavailableConflictingState("timeLapseSpeed", activeSpeeds)
        return PortResult.Observed(
            PixelCameraState.TimeLapse(
                speed = activeSpeeds.singleOrNull(),
                recording = recording,
                lens = if (PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE in active) {
                    LensSelection.REAR_MAIN
                } else {
                    null
                },
            ),
        )
    }

    private fun unavailableConflictingState(
        dimension: String,
        values: Set<*>,
    ): PortResult.Unavailable = PortResult.Unavailable(
        AutomationFailure(
            code = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
            message = "Pixel Camera $dimension state is missing or conflicting",
            context = mapOf("matches" to values.joinToString(",")),
        ),
    )

    private fun validateProfile(profile: PixelCameraProfile): AutomationFailure? {
        if (profile.environment.cameraPackage != PIXEL_CAMERA_PACKAGE) {
            return AutomationFailure(
                code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
                message = "The profile does not target the supported Pixel Camera package",
            )
        }
        if (profile.selectorSchemaVersion != PixelCameraSelectorSchema.CURRENT_VERSION) {
            return AutomationFailure(
                code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
                message = "The profile selector schema is not supported by this Lenswake build",
                context = mapOf(
                    "profileSchema" to profile.selectorSchemaVersion.toString(),
                    "supportedSchema" to PixelCameraSelectorSchema.CURRENT_VERSION.toString(),
                ),
            )
        }
        val currentEnvironment = when (val result = environmentProbe()) {
            is PortResult.Observed -> result.value
            is PortResult.Unavailable -> return result.failure
        }
        return when (profile.compatibilityFor(currentEnvironment)) {
            ProfileCompatibility.VERIFIED -> null
            ProfileCompatibility.PROBABLY_COMPATIBLE,
            ProfileCompatibility.NEEDS_REHEARSAL,
            -> AutomationFailure(
                code = AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL,
                message = "Scheduled automation requires a profile verified for the current environment",
            )
            ProfileCompatibility.INCOMPATIBLE -> AutomationFailure(
                code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
                message = "The current device or Pixel Camera package is incompatible with the profile",
            )
        }
    }

    private fun missingActionFailure(action: AutomationAction): AutomationFailure = failure(
        code = when (action) {
            AutomationAction.SELECT_VIDEO -> AutomationFailureCode.VIDEO_MODE_NOT_FOUND
            AutomationAction.SELECT_TIME_LAPSE -> AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND
            AutomationAction.SELECT_TIME_LAPSE_SPEED -> AutomationFailureCode.TIME_LAPSE_SPEED_NOT_FOUND
            AutomationAction.SELECT_REAR_MAIN_LENS -> AutomationFailureCode.LENS_NOT_FOUND
            AutomationAction.START_RECORDING -> AutomationFailureCode.RECORD_CONTROL_NOT_FOUND
            AutomationAction.STOP_RECORDING -> AutomationFailureCode.STOP_CONTROL_NOT_FOUND
        },
        message = "No safe Pixel Camera target was available for $action",
    )

    private fun failure(code: AutomationFailureCode, message: String): AutomationFailure =
        AutomationFailure(code = code, message = message)

    private companion object {
        val SPEED_SIGNALS = mapOf(
            PixelCameraStateSignal.TIME_LAPSE_SPEED_AUTO_ACTIVE to TimeLapseSpeed.AUTO,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X5_ACTIVE to TimeLapseSpeed.X5,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X10_ACTIVE to TimeLapseSpeed.X10,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE to TimeLapseSpeed.X30,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE to TimeLapseSpeed.X120,
        )
    }
}

internal interface PixelCameraAccessibilityGateway {
    suspend fun snapshot(): AccessibilitySnapshotResult

    suspend fun dispatchClick(nodePath: String): AccessibilityDispatchResult
}

private object RuntimePixelCameraAccessibilityGateway : PixelCameraAccessibilityGateway {
    override suspend fun snapshot(): AccessibilitySnapshotResult = PixelCameraAccessibilityRuntime.snapshot()

    override suspend fun dispatchClick(nodePath: String): AccessibilityDispatchResult =
        PixelCameraAccessibilityRuntime.dispatchClick(nodePath)
}
