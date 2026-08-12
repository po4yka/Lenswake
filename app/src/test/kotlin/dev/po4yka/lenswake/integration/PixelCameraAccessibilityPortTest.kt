package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.GestureProfile
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.NormalizedPoint
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.PixelCameraDialogProfile
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PixelCameraAccessibilityPortTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `configured profile gesture is dispatched when no semantic target is available`() = runTest {
        val fallbackPoint = NormalizedPoint(x = 0.5f, y = 0.85f)
        val gateway = FakeAccessibilityGateway(nodes = emptyList())
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.START_RECORDING to GestureProfile(fallbackPoint),
            ),
        )

        val result = port(gateway = gateway).startRecording(CaptureMode.TIME_LAPSE, profileUse(profile))

        val dispatched = assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE, dispatched.method)
        assertEquals(fallbackPoint, gateway.profileGesturePoint)
    }

    @Test
    fun `configured speed profile gesture is dispatched when speed selector is absent`() = runTest {
        val fallbackPoint = NormalizedPoint(x = 0.72f, y = 0.88f)
        val gateway = FakeAccessibilityGateway(nodes = emptyList())
        val profile = profile().copy(
            speedTargets = emptyMap(),
            fallbackGestures = mapOf(
                AutomationAction.SELECT_TIME_LAPSE_SPEED to GestureProfile(fallbackPoint),
            ),
        )

        val result = port(gateway = gateway).selectTimeLapseSpeed(
            speed = TimeLapseSpeed.X120,
            profileUse = profileUse(profile),
        )

        val dispatched = assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE, dispatched.method)
        assertEquals(fallbackPoint, gateway.profileGesturePoint)
    }

    @Test
    fun `configured profile gesture follows a rejected node-bounds gesture`() = runTest {
        val fallbackPoint = NormalizedPoint(x = 0.44f, y = 0.66f)
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.GestureRejected,
        )
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to GestureProfile(fallbackPoint),
            ),
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse(profile))

        val dispatched = assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE, dispatched.method)
        assertEquals(fallbackPoint, gateway.profileGesturePoint)
    }

    @Test
    fun `ambiguous semantic target does not fall through to profile gesture`() = runTest {
        val fallbackPoint = NormalizedPoint(x = 0.44f, y = 0.66f)
        val target = node(LENS_ACTION_RESOURCE)
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(
                target.copy(id = "duplicate-lens-a"),
                target.copy(id = "duplicate-lens-b"),
            ),
        )
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to GestureProfile(fallbackPoint),
            ),
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse(profile))

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.UI_TARGET_AMBIGUOUS, rejected.failure.code)
        assertEquals(null, gateway.profileGesturePoint)
        assertEquals(null, gateway.clickedNode)
    }

    @Test
    fun `below-threshold semantic candidate does not fall through to profile gesture`() = runTest {
        val fallbackPoint = NormalizedPoint(x = 0.44f, y = 0.66f)
        val gateway = FakeAccessibilityGateway(nodes = listOf(node(LENS_ACTION_RESOURCE)))
        val profile = profile().copy(
            targets = profile().targets + mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to selectorSet(LENS_ACTION_RESOURCE).copy(
                    minimumScore = 110,
                ),
            ),
            fallbackGestures = mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to GestureProfile(fallbackPoint),
            ),
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse(profile))

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.UI_TARGET_CONFIDENCE_TOO_LOW, rejected.failure.code)
        assertEquals(null, gateway.profileGesturePoint)
        assertEquals(null, gateway.clickedNode)
    }

}

class PixelCameraAccessibilityDialogTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `typed dialog takes precedence over the generic unknown rule`() = runTest {
        val dialog = PixelCameraDialogKind.VIDEO_DURATION_LIMIT_REACHED
        val messageResource = "android:id/message"
        val message = "Video reached the duration limit."
        val recoveryResource = "android:id/button1"
        val configuredProfile = profile().copy(
            dialogProfiles = mapOf(
                dialog to PixelCameraDialogProfile(
                    presence = UiSelectorSet(
                        selectors = listOf(
                            UiSelector(
                                packageName = CAMERA_PACKAGE,
                                resourceId = messageResource,
                                text = message,
                                role = "android.widget.TextView",
                                requiresClickable = false,
                            ),
                        ),
                        minimumScore = 150,
                    ),
                    recoveryTarget = selectorSet(recoveryResource),
                ),
                PixelCameraDialogKind.UNKNOWN to PixelCameraDialogProfile(
                    presence = UiSelectorSet(
                        selectors = listOf(
                            UiSelector(
                                packageName = CAMERA_PACKAGE,
                                resourceId = messageResource,
                                role = "android.widget.TextView",
                                requiresClickable = false,
                            ),
                        ),
                        minimumScore = 120,
                    ),
                    recoveryTarget = null,
                ),
            ),
        )
        val messageNode = node(messageResource).copy(
            role = "android.widget.TextView",
            text = message,
            clickable = false,
        )
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(messageNode, node(recoveryResource)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        val observed = assertInstanceOf(
            PortResult.Observed::class.java,
            port.inspect(profileUse(configuredProfile)),
        )
        assertEquals(PixelCameraState.Dialog(dialog), observed.value)

        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.recoverDialog(dialog, profileUse(configuredProfile)),
        )
        assertEquals("node-$recoveryResource", gateway.clickedNode?.id)
    }

    @Test
    fun `typed dialog is observed and recovered through its dedicated target`() = runTest {
        val dialog = PixelCameraDialogKind.VIDEO_DURATION_LIMIT_REACHED
        val dialogSignal = "video-duration-limit-dialog"
        val recoveryTarget = "video-duration-limit-ok"
        val configuredProfile = profile().copy(
            dialogProfiles = mapOf(
                dialog to PixelCameraDialogProfile(
                    presence = selectorSet(dialogSignal),
                    recoveryTarget = selectorSet(recoveryTarget),
                ),
            ),
        )
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(dialogSignal), node(recoveryTarget)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        val observed = assertInstanceOf(
            PortResult.Observed::class.java,
            port.inspect(profileUse(configuredProfile)),
        )
        assertEquals(PixelCameraState.Dialog(dialog), observed.value)

        val recovered = assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.recoverDialog(dialog, profileUse(configuredProfile)),
        )
        assertEquals(InteractionMethod.ACCESSIBILITY_ACTION, recovered.method)
        assertEquals("node-$recoveryTarget", gateway.clickedNode?.id)
    }

    @Test
    fun `unknown dialog fails closed without dispatching a recovery target`() = runTest {
        val dialogSignal = "unknown-dialog"
        val configuredProfile = profile().copy(
            dialogProfiles = mapOf(
                PixelCameraDialogKind.UNKNOWN to PixelCameraDialogProfile(
                    presence = selectorSet(dialogSignal),
                    recoveryTarget = null,
                ),
            ),
        )
        val gateway = FakeAccessibilityGateway(nodes = listOf(node(dialogSignal)))
        val port = port(gateway = gateway)

        val observed = assertInstanceOf(
            PortResult.Observed::class.java,
            port.inspect(profileUse(configuredProfile)),
        )
        assertEquals(PixelCameraState.Dialog(PixelCameraDialogKind.UNKNOWN), observed.value)

        val rejected = assertInstanceOf(
            ActionDispatch.Rejected::class.java,
            port.recoverDialog(PixelCameraDialogKind.UNKNOWN, profileUse(configuredProfile)),
        )
        assertEquals(AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG, rejected.failure.code)
        assertEquals(null, gateway.clickedNode)
    }
}

class PixelCameraAccessibilityStateTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `time lapse reports rear main lens only when its profile signal matches`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(
            PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            observed.value,
        )
    }

    @Test
    fun `video-only profile is inspected without unrelated Time Lapse signals`() = runTest {
        val videoProfile = profile().copy(
            targets = mapOf(
                AutomationAction.SELECT_VIDEO to selectorSet(VIDEO_ACTION_RESOURCE),
                AutomationAction.SELECT_FRONT_LENS to selectorSet(FRONT_LENS_ACTION_RESOURCE),
                AutomationAction.START_VIDEO_RECORDING to selectorSet(START_VIDEO_ACTION_RESOURCE),
                AutomationAction.STOP_VIDEO_RECORDING to selectorSet(STOP_VIDEO_ACTION_RESOURCE),
            ),
            speedTargets = emptyMap(),
            stateSignals = setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.FRONT_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ).associateWith { selectorSet(it.name) },
        )
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.FRONT_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse(videoProfile))

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(PixelCameraState.Video(recording = false, lens = LensSelection.FRONT), observed.value)
    }

    @Test
    fun `video inspection and recording controls use video-specific profile routes`() = runTest {
        val videoProfile = profile().copy(
            targets = mapOf(
                AutomationAction.SELECT_VIDEO to selectorSet(VIDEO_ACTION_RESOURCE),
                AutomationAction.SELECT_FRONT_LENS to selectorSet(FRONT_LENS_ACTION_RESOURCE),
                AutomationAction.START_VIDEO_RECORDING to selectorSet(START_VIDEO_ACTION_RESOURCE),
                AutomationAction.STOP_VIDEO_RECORDING to selectorSet(STOP_VIDEO_ACTION_RESOURCE),
            ),
            speedTargets = emptyMap(),
            stateSignals = setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.FRONT_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ).associateWith { selectorSet(it.name) },
        )
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.FRONT_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
            ) + listOf(
                node(START_VIDEO_ACTION_RESOURCE),
                node(STOP_VIDEO_ACTION_RESOURCE),
            ),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        val observed = assertInstanceOf(
            PortResult.Observed::class.java,
            port.inspect(profileUse(videoProfile)),
        )
        assertEquals(PixelCameraState.Video(recording = true, lens = LensSelection.FRONT), observed.value)
        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.startRecording(CaptureMode.VIDEO, profileUse(videoProfile)),
        )
        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.stopRecording(CaptureMode.VIDEO, profileUse(videoProfile)),
        )
        assertEquals(
            listOf("node-$START_VIDEO_ACTION_RESOURCE", "node-$STOP_VIDEO_ACTION_RESOURCE"),
            gateway.clickedNodes.map(UiNodeSnapshot::id),
        )
    }

    @Test
    fun `night sight inspection and recording controls use night-specific profile routes`() = runTest {
        val nightProfile = profile().copy(
            targets = mapOf(
                AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE to selectorSet(NIGHT_ACTION_RESOURCE),
                AutomationAction.SELECT_REAR_ULTRAWIDE_LENS to selectorSet(ULTRAWIDE_ACTION_RESOURCE),
                AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING to
                    selectorSet(START_NIGHT_ACTION_RESOURCE),
                AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING to
                    selectorSet(STOP_NIGHT_ACTION_RESOURCE),
            ),
            speedTargets = emptyMap(),
            stateSignals = setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ).associateWith { selectorSet(it.name) },
        )
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
            ) + listOf(
                node(START_NIGHT_ACTION_RESOURCE),
                node(STOP_NIGHT_ACTION_RESOURCE),
            ),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        val observed = assertInstanceOf(
            PortResult.Observed::class.java,
            port.inspect(profileUse(nightProfile)),
        )
        assertEquals(
            PixelCameraState.NightSightTimeLapse(
                recording = true,
                lens = LensSelection.REAR_ULTRAWIDE,
            ),
            observed.value,
        )
        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.startRecording(CaptureMode.NIGHT_SIGHT_TIME_LAPSE, profileUse(nightProfile)),
        )
        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.stopRecording(CaptureMode.NIGHT_SIGHT_TIME_LAPSE, profileUse(nightProfile)),
        )
        assertEquals(
            listOf("node-$START_NIGHT_ACTION_RESOURCE", "node-$STOP_NIGHT_ACTION_RESOURCE"),
            gateway.clickedNodes.map(UiNodeSnapshot::id),
        )
    }

}

class PixelCameraAccessibilityFailureTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `inspection rejects an accessibility snapshot whose root could not refresh`() = runTest {
        val result = port(
            gateway = FakeAccessibilityGateway(
                snapshotResult = AccessibilitySnapshotResult.RefreshFailed,
            ),
        ).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED, unavailable.failure.code)
    }

    @Test
    fun `dispatch rejects when the active root could not refresh before path resolution`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.RefreshFailed,
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse())

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED, rejected.failure.code)
        assertEquals("node-$LENS_ACTION_RESOURCE", gateway.clickedNode?.id)
    }

    @Test
    fun `dispatch reports a typed failure when the live node no longer matches the selected target`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.TargetIdentityChanged,
        )
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to GestureProfile(NormalizedPoint(0.4f, 0.6f)),
            ),
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse(profile))

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.UI_TARGET_CHANGED, rejected.failure.code)
        assertEquals(AutomationAction.SELECT_REAR_MAIN_LENS.name, rejected.failure.context["action"])
        assertEquals(null, gateway.profileGesturePoint)
    }

    @Test
    fun `semantic action remains preferred over configured profile gesture`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.SELECT_REAR_MAIN_LENS to GestureProfile(NormalizedPoint(0.4f, 0.6f)),
            ),
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse(profile))

        val dispatched = assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(InteractionMethod.ACCESSIBILITY_ACTION, dispatched.method)
        assertEquals("100", dispatched.metadata["selectorScore"])
        assertEquals("100", dispatched.metadata["selectorMinimumScore"])
        assertEquals("0", dispatched.metadata["selectorIndex"])
        assertEquals("RESOURCE_ID", dispatched.metadata["selectorSignals"])
        assertEquals(null, gateway.profileGesturePoint)
    }

    @Test
    fun `profile gesture fails when Pixel Camera is no longer foreground`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = emptyList(),
            profileGestureResult = AccessibilityDispatchResult.TargetNotEligible,
        )
        val profile = profile().copy(
            fallbackGestures = mapOf(
                AutomationAction.START_RECORDING to GestureProfile(NormalizedPoint(0.5f, 0.85f)),
            ),
        )

        val result = port(gateway = gateway).startRecording(CaptureMode.TIME_LAPSE, profileUse(profile))

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND, rejected.failure.code)
    }

    @Test
    fun `close speed control dispatches global Back without selecting a node`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
            globalBackResult = AccessibilityDispatchResult.GlobalActionDispatched,
        )

        val result = port(gateway = gateway).closeTimeLapseSpeedControl(TimeLapseSpeed.X120, profileUse())

        val dispatched = assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(dev.po4yka.lenswake.core.InteractionMethod.ACCESSIBILITY_ACTION, dispatched.method)
        assertEquals(1, gateway.globalBackCalls)
        assertEquals(1, gateway.snapshotCalls)
        assertEquals(null, gateway.clickedNode)
        assertEquals(
            "node-${PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN.name}",
            gateway.globalBackNode?.id,
        )
    }

    @Test
    fun `close speed control maps rejected global Back to typed failure`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
            globalBackResult = AccessibilityDispatchResult.GlobalActionRejected,
        )

        val result = port(gateway = gateway).closeTimeLapseSpeedControl(TimeLapseSpeed.X120, profileUse())

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED, rejected.failure.code)
        assertEquals(1, gateway.globalBackCalls)
    }

    @Test
    fun `close speed control refuses global Back when fresh picker state changed`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
            globalBackResult = AccessibilityDispatchResult.GlobalActionDispatched,
        )

        val result = port(gateway = gateway).closeTimeLapseSpeedControl(TimeLapseSpeed.X120, profileUse())

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED, rejected.failure.code)
        assertEquals(0, gateway.globalBackCalls)
    }

}

class PixelCameraAccessibilityStateSafetyTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `time lapse lens remains unknown when configured rear main signal does not match`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(
            PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = false,
                lens = null,
            ),
            observed.value,
        )
    }

    @Test
    fun `recording with obscured speed and lens controls is exposed as safe stop state`() = runTest {
        val optionalControls = listOf(
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
        )

        for (obscured in optionalControls) {
            val gateway = FakeAccessibilityGateway(
                nodes = activeSignals(
                    PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                    PixelCameraStateSignal.RECORDING_ACTIVE,
                ) + optionalControls.map { signal -> node(signal.name).copy(visible = signal != obscured) },
                dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
            )
            val port = port(gateway = gateway)

            val result = port.inspect(profileUse())

            val observed = assertInstanceOf(PortResult.Observed::class.java, result)
            assertEquals(PixelCameraState.RecordingUnknownMode, observed.value)
            assertInstanceOf(
                ActionDispatch.Dispatched::class.java,
                port.stopRecording(CaptureMode.TIME_LAPSE, profileUse()),
            )
            assertEquals(
                "node-${PixelCameraStateSignal.RECORDING_ACTIVE.name}",
                gateway.clickedNode?.id,
            )
        }
    }

    @Test
    fun `recording keeps exact time lapse state when speed and lens are observable`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(
            PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            observed.value,
        )
    }

    @Test
    fun `recording tolerates ambiguous exposed speed or lens controls only for safe stop`() = runTest {
        val optionalControls = listOf(
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
        )

        for (ambiguousSignal in optionalControls) {
            val duplicatedControl = node(ambiguousSignal.name)
            val gateway = FakeAccessibilityGateway(
                nodes = activeSignals(
                    PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                    PixelCameraStateSignal.RECORDING_ACTIVE,
                    *(optionalControls - ambiguousSignal).toTypedArray(),
                ) + listOf(
                    duplicatedControl.copy(id = "partially-visible-${ambiguousSignal.name}"),
                    duplicatedControl.copy(id = "partially-obscured-${ambiguousSignal.name}"),
                ),
            )

            val result = port(gateway = gateway).inspect(profileUse())

            val observed = assertInstanceOf(PortResult.Observed::class.java, result)
            assertEquals(PixelCameraState.RecordingUnknownMode, observed.value)
        }
    }

    @Test
    fun `non-recording state still rejects ambiguous speed controls`() = runTest {
        val duplicatedSpeed = node(PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE.name)
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ) + listOf(
                duplicatedSpeed.copy(id = "visible-speed-a"),
                duplicatedSpeed.copy(id = "visible-speed-b"),
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.UI_TARGET_AMBIGUOUS, unavailable.failure.code)
    }

    @Test
    fun `recording state still rejects an ambiguous stop control`() = runTest {
        val duplicatedStop = node(PixelCameraStateSignal.RECORDING_ACTIVE.name)
        val gateway = FakeAccessibilityGateway(
            nodes = activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
            ) + listOf(
                duplicatedStop.copy(id = "stop-a"),
                duplicatedStop.copy(id = "stop-b"),
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.UI_TARGET_AMBIGUOUS, unavailable.failure.code)
    }

    @Test
    fun `partially configured future lens does not break supported capture inspection`() = runTest {
        val baseline = profile()
        val partiallyConfiguredFrontVideo = baseline.copy(
            targets = baseline.targets + mapOf(
                AutomationAction.SELECT_FRONT_LENS to baseline.targets.getValue(AutomationAction.SELECT_VIDEO),
                AutomationAction.START_VIDEO_RECORDING to baseline.targets.getValue(AutomationAction.START_RECORDING),
                AutomationAction.STOP_VIDEO_RECORDING to baseline.targets.getValue(AutomationAction.STOP_RECORDING),
            ),
            stateSignals = baseline.stateSignals - PixelCameraStateSignal.FRONT_LENS_ACTIVE,
        )

        val result = port(gateway = FakeAccessibilityGateway(activeSignals(
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
            PixelCameraStateSignal.NOT_RECORDING,
        ))).inspect(
            profileUse(partiallyConfiguredFrontVideo),
        )

        assertInstanceOf(PortResult.Observed::class.java, result)
    }

}

class PixelCameraAccessibilityActionRoutingTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `4k and 60 fps actions use fresh profile-defined targets`() = runTest {
        val resolutionTarget = node(VIDEO_4K_ACTION_RESOURCE)
        val frameRateTarget = node(VIDEO_60_ACTION_RESOURCE)
        val configured = profile().copy(
            targets = profile().targets + mapOf(
                AutomationAction.SELECT_VIDEO_RESOLUTION_4K to selectorSet(VIDEO_4K_ACTION_RESOURCE),
                AutomationAction.SELECT_VIDEO_FRAME_RATE_60 to selectorSet(VIDEO_60_ACTION_RESOURCE),
            ),
        )
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(resolutionTarget, frameRateTarget),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        assertInstanceOf(ActionDispatch.Dispatched::class.java, port.selectVideoResolution4k(profileUse(configured)))
        assertEquals(resolutionTarget, gateway.clickedNode)
        assertInstanceOf(ActionDispatch.Dispatched::class.java, port.selectVideoFrameRate60(profileUse(configured)))
        assertEquals(frameRateTarget, gateway.clickedNode)
        assertEquals(2, gateway.snapshotCalls)
    }

    @Test
    fun `rear main lens action dispatches only its profile-defined target`() = runTest {
        val target = node(LENS_ACTION_RESOURCE).copy(
            role = "android.widget.Button",
            contentDescription = "1x",
            text = "Main lens",
            selected = true,
        )
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(target),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )

        val result = port(gateway = gateway).selectLens(LensSelection.REAR_MAIN, profileUse())

        assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals(target, gateway.clickedNode)
    }

    @Test
    fun `speed control opener and speed option are separate semantic dispatches`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(SPEED_CONTROL_ACTION_RESOURCE), node(SPEED_X120_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        assertInstanceOf(ActionDispatch.Dispatched::class.java, port.openTimeLapseSpeedControl(profileUse()))
        assertEquals(listOf("node-$SPEED_CONTROL_ACTION_RESOURCE"), gateway.clickedNodes.map(UiNodeSnapshot::id))

        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.selectTimeLapseSpeed(TimeLapseSpeed.X120, profileUse()),
        )
        assertEquals(
            listOf("node-$SPEED_CONTROL_ACTION_RESOURCE", "node-$SPEED_X120_ACTION_RESOURCE"),
            gateway.clickedNodes.map(UiNodeSnapshot::id),
        )
    }

    @Test
    fun `visible speed option is observable picker-open state without claiming it selected`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(
            PixelCameraState.TimeLapseSpeedPicker(
                speed = null,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            observed.value,
        )
    }

    @Test
    fun `persistent speed picker reports the selected time lapse speed`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profileUse())

        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        assertEquals(
            PixelCameraState.TimeLapseSpeedPicker(
                speed = TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            observed.value,
        )
    }

    @Test
    fun `inspection ignores incomplete unsupported time lapse capability`() = runTest {
        val withoutPickerSignal = profile().copy(
            stateSignals = profile().stateSignals - PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
        )

        val result = port(gateway = FakeAccessibilityGateway(activeSignals(
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
            PixelCameraStateSignal.NOT_RECORDING,
        ))).inspect(
            profileUse(withoutPickerSignal),
        )

        assertInstanceOf(PortResult.Observed::class.java, result)
    }

}

class PixelCameraAccessibilityProfileValidationTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `unapproved system build is incompatible before accessibility inspection`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val current = environment().copy(
            androidBuildFingerprint =
                "google/husky/husky:17/CP2A.260805.005/1:user/release-keys",
        )

        val result = port(currentEnvironment = current, gateway = gateway).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `stored probable compatibility requires rehearsal even on exact environment`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val probablyCompatible = profile().copy(compatibility = ProfileCompatibility.PROBABLY_COMPATIBLE)

        val result = port(gateway = gateway).inspect(profileUse(probablyCompatible))

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `unsupported selector schema is rejected before accessibility inspection`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val unsupported = profile().copy(
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION + 1,
        )

        val result = port(gateway = gateway).inspect(
            profileUse(unsupported, ProfileUse.Kind.REHEARSAL),
        )

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `rehearsal admits every non-incompatible persisted compatibility`() = runTest {
        for (compatibility in listOf(
            ProfileCompatibility.VERIFIED,
            ProfileCompatibility.PROBABLY_COMPATIBLE,
            ProfileCompatibility.NEEDS_REHEARSAL,
        )) {
            val gateway = FakeAccessibilityGateway(activeSignals(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ))
            val candidate = profile().copy(compatibility = compatibility)

            val result = port(gateway = gateway).inspect(
                profileUse(candidate, ProfileUse.Kind.REHEARSAL),
            )

            val observed = assertInstanceOf(PortResult.Observed::class.java, result)
            assertEquals(PixelCameraState.Photo, observed.value)
            assertEquals(1, gateway.snapshotCalls)
        }
    }

    @Test
    fun `rehearsal rejects incompatible environment before accessibility inspection`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val otherDevice = environment().copy(deviceModel = "Pixel 9 Pro")

        val result = port(currentEnvironment = otherDevice, gateway = gateway).inspect(
            profileUse(kind = ProfileUse.Kind.REHEARSAL),
        )

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `rehearsal rejects a profile explicitly marked incompatible`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val incompatible = profile().copy(compatibility = ProfileCompatibility.INCOMPATIBLE)

        val result = port(gateway = gateway).inspect(
            profileUse(incompatible, ProfileUse.Kind.REHEARSAL),
        )

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `rehearsal rejects wrong camera package before environment inspection`() = runTest {
        var environmentProbeCalls = 0
        val gateway = FakeAccessibilityGateway(activeSignals())
        val wrongPackage = profile().copy(
            environment = environment().copy(cameraPackage = "example.camera"),
            targets = emptyMap(),
            speedTargets = emptyMap(),
            stateSignals = emptyMap(),
        )
        val port = PixelCameraAccessibilityPort(
            cameraLauncher = { error("Camera launch is outside this adapter test") },
            selectorMatcher = SelectorMatcher(),
            environmentProbe = {
                environmentProbeCalls += 1
                PortResult.Observed(environment())
            },
            accessibilityGateway = gateway,
        )

        val result = port.inspect(profileUse(wrongPackage, ProfileUse.Kind.REHEARSAL))

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
        assertEquals(0, environmentProbeCalls)
        assertEquals(0, gateway.snapshotCalls)
    }

}

abstract class PixelCameraAccessibilityPortTestFixture {
    protected fun port(
        currentEnvironment: PixelCameraEnvironment = environment(),
        gateway: FakeAccessibilityGateway,
    ): PixelCameraAccessibilityPort = PixelCameraAccessibilityPort(
        cameraLauncher = { error("Camera launch is outside this adapter test") },
        selectorMatcher = SelectorMatcher(),
        environmentProbe = { PortResult.Observed(currentEnvironment) },
        accessibilityGateway = gateway,
    )

    protected fun profileUse(
        profile: PixelCameraProfile = profile(),
        kind: ProfileUse.Kind = ProfileUse.Kind.UNATTENDED,
    ): ProfileUse = ProfileUse(profile, kind)

    protected fun profile(): PixelCameraProfile {
        val requiredSignals = setOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
            PixelCameraStateSignal.RECORDING_ACTIVE,
            PixelCameraStateSignal.NOT_RECORDING,
        )
        return PixelCameraProfile(
            id = ProfileId("profile"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            targets = mapOf(
                AutomationAction.SELECT_VIDEO to selectorSet(VIDEO_ACTION_RESOURCE),
                AutomationAction.SELECT_TIME_LAPSE to selectorSet(TIME_LAPSE_ACTION_RESOURCE),
                AutomationAction.SELECT_REAR_MAIN_LENS to selectorSet(LENS_ACTION_RESOURCE),
                AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL to selectorSet(SPEED_CONTROL_ACTION_RESOURCE),
                AutomationAction.START_RECORDING to selectorSet(START_RECORD_ACTION_RESOURCE),
                AutomationAction.STOP_RECORDING to selectorSet(PixelCameraStateSignal.RECORDING_ACTIVE.name),
            ),
            speedTargets = mapOf(TimeLapseSpeed.X120 to selectorSet(SPEED_X120_ACTION_RESOURCE)),
            stateSignals = requiredSignals.associateWith { selectorSet(it.name) },
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
        )
    }

    protected fun environment(): PixelCameraEnvironment = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        deviceCodename = "husky",
        androidSdk = 37,
        androidBuildFingerprint =
            "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
        cameraPackage = SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName,
        cameraVersionCode = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode,
        cameraSigningCertificateSha256 =
            SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
        localeTag = "en-US",
        displayWidthPx = 1_344,
        displayHeightPx = 2_992,
        densityDpi = 480,
    )

    protected fun activeSignals(vararg signals: PixelCameraStateSignal): List<UiNodeSnapshot> =
        signals.map { node(it.name) }

    protected fun selectorSet(resourceId: String): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            UiSelector(
                packageName = CAMERA_PACKAGE,
                resourceId = resourceId,
                requiresClickable = false,
            ),
        ),
        minimumScore = 100,
    )

    protected fun node(resourceId: String): UiNodeSnapshot = UiNodeSnapshot(
        id = "node-$resourceId",
        packageName = CAMERA_PACKAGE,
        resourceId = resourceId,
        role = null,
        contentDescription = null,
        text = null,
        bounds = null,
        visible = true,
        clickable = true,
        selected = false,
        enabled = true,
    )

    protected class FakeAccessibilityGateway(
        private val nodes: List<UiNodeSnapshot> = emptyList(),
        private val dispatchResult: AccessibilityDispatchResult = AccessibilityDispatchResult.TargetNotFound,
        private val profileGestureResult: AccessibilityDispatchResult = AccessibilityDispatchResult.GestureSubmitted,
        private val globalBackResult: AccessibilityDispatchResult = AccessibilityDispatchResult.GlobalActionRejected,
        private val snapshotResult: AccessibilitySnapshotResult? = null,
    ) : PixelCameraAccessibilityGateway {
        var snapshotCalls: Int = 0
            private set
        var clickedNode: UiNodeSnapshot? = null
            private set
        val clickedNodes = mutableListOf<UiNodeSnapshot>()
        var globalBackCalls: Int = 0
            private set
        var globalBackNode: UiNodeSnapshot? = null
            private set
        var profileGesturePoint: NormalizedPoint? = null
            private set

        override suspend fun snapshot(): AccessibilitySnapshotResult {
            snapshotCalls += 1
            return snapshotResult ?: AccessibilitySnapshotResult.Available(nodes = nodes, truncated = false)
        }

        override suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult {
            clickedNode = node
            clickedNodes += node
            return dispatchResult
        }

        override suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult {
            profileGesturePoint = point
            return profileGestureResult
        }

        override suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult {
            globalBackCalls += 1
            globalBackNode = pickerNode
            return globalBackResult
        }
    }

    protected companion object {
        const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"
        const val VIDEO_ACTION_RESOURCE = "profile.mode.video"
        const val VIDEO_4K_ACTION_RESOURCE = "profile.video.4k"
        const val VIDEO_60_ACTION_RESOURCE = "profile.video.60"
        const val TIME_LAPSE_ACTION_RESOURCE = "profile.mode.time-lapse"
        const val NIGHT_ACTION_RESOURCE = "profile.mode.night-sight-time-lapse"
        const val LENS_ACTION_RESOURCE = "profile.lens.rear-main"
        const val ULTRAWIDE_ACTION_RESOURCE = "profile.lens.rear-ultrawide"
        const val FRONT_LENS_ACTION_RESOURCE = "profile.lens.front"
        const val SPEED_CONTROL_ACTION_RESOURCE = "profile.speed.open"
        const val SPEED_X120_ACTION_RESOURCE = "profile.speed.x120"
        const val START_RECORD_ACTION_RESOURCE = "profile.record.start"
        const val START_VIDEO_ACTION_RESOURCE = "profile.video.start"
        const val STOP_VIDEO_ACTION_RESOURCE = "profile.video.stop"
        const val START_NIGHT_ACTION_RESOURCE = "profile.night.start"
        const val STOP_NIGHT_ACTION_RESOURCE = "profile.night.stop"
    }
}
