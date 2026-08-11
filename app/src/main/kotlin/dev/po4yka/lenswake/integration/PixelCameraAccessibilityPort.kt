package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PixelCameraPort
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.platform.CameraLaunchDispatch
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
    selectorMatcher: SelectorMatcher,
    environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val accessibilityGateway: PixelCameraAccessibilityGateway,
) : PixelCameraPort {
    private val profileValidator = PixelCameraProfileValidator(environmentProbe)
    private val stateInferer = PixelCameraStateInferer(selectorMatcher)
    private val actionDispatcher = PixelCameraActionDispatcher(selectorMatcher, accessibilityGateway)
    private val speedControlCloser = TimeLapseSpeedControlCloser(
        selectorMatcher = selectorMatcher,
        gateway = accessibilityGateway,
        stateInferer = stateInferer,
    )

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

    override suspend fun inspect(profileUse: ProfileUse): PortResult<PixelCameraState> =
        profileValidator.validate(profileUse)?.let { PortResult.Unavailable(it) }
            ?: stateInferer.inspect(accessibilityGateway.snapshot(), profileUse.profile)

    override suspend fun launchSecureCamera(profileUse: ProfileUse): ActionDispatch =
        profileValidator.validate(profileUse)?.let(ActionDispatch::Rejected)
            ?: when (val result = cameraLauncher()) {
                is CameraLaunchDispatch.Dispatched -> ActionDispatch.Dispatched(
                    InteractionMethod.STANDARD_ANDROID_API,
                )

                is CameraLaunchDispatch.Unavailable -> ActionDispatch.Rejected(
                    AutomationFailure(
                        code = result.failureCode(),
                        message = result.capability.detail,
                        context = mapOf("platformCapability" to result.capability.code.name),
                    ),
                )
            }

    override suspend fun selectVideo(profileUse: ProfileUse): ActionDispatch =
        actionDispatcher.dispatchValidated(profileUse, profileValidator, videoAction)

    override suspend fun selectTimeLapse(profileUse: ProfileUse): ActionDispatch =
        actionDispatcher.dispatchValidated(profileUse, profileValidator, timeLapseAction)

    override suspend fun selectNightSightTimeLapse(profileUse: ProfileUse): ActionDispatch =
        actionDispatcher.dispatchValidated(profileUse, profileValidator, nightSightTimeLapseAction)

    override suspend fun openTimeLapseSpeedControl(profileUse: ProfileUse): ActionDispatch =
        actionDispatcher.dispatchValidated(profileUse, profileValidator, openSpeedControlAction)

    override suspend fun selectTimeLapseSpeed(
        speed: TimeLapseSpeed,
        profileUse: ProfileUse,
    ): ActionDispatch = actionDispatcher.dispatchValidated(
        profileUse = profileUse,
        validator = profileValidator,
        action = selectSpeedAction,
        speed = speed,
    )

    override suspend fun closeTimeLapseSpeedControl(
        expectedSpeed: TimeLapseSpeed?,
        profileUse: ProfileUse,
    ): ActionDispatch = profileValidator.validate(profileUse)?.let(ActionDispatch::Rejected)
        ?: speedControlCloser.close(expectedSpeed, profileUse.profile)

    override suspend fun selectLens(
        lens: LensSelection,
        profileUse: ProfileUse,
    ): ActionDispatch = actionDispatcher.dispatchValidated(
        profileUse,
        profileValidator,
        lensActions.getValue(lens),
    )

    override suspend fun startRecording(
        mode: CaptureMode,
        profileUse: ProfileUse,
    ): ActionDispatch = actionDispatcher.dispatchValidated(
        profileUse,
        profileValidator,
        mode.startAction,
    )

    override suspend fun stopRecording(
        mode: CaptureMode,
        profileUse: ProfileUse,
    ): ActionDispatch = actionDispatcher.dispatchValidated(
        profileUse,
        profileValidator,
        mode.stopAction,
    )
}

private fun CameraLaunchDispatch.Unavailable.failureCode(): AutomationFailureCode =
    when (capability.code) {
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
    }
