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
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PixelCameraAccessibilityPortTest {
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
    fun `inspection rejects an accessibility snapshot whose root could not refresh`() = runTest {
        val result = port(
            gateway = FakeAccessibilityGateway(
                snapshotResult = AccessibilitySnapshotResult.RootRefreshFailed,
            ),
        ).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED, unavailable.failure.code)
    }

    @Test
    fun `dispatch rejects when the active root could not refresh before path resolution`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.RootRefreshFailed,
        )

        val result = port(gateway = gateway).selectRearMainLens(profileUse())

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED, rejected.failure.code)
        assertEquals("node-$LENS_ACTION_RESOURCE", gateway.clickedNodePath)
    }

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
    fun `inspection fails closed when profile lacks rear main observation signal`() = runTest {
        val withoutLensSignal = profile().copy(
            stateSignals = profile().stateSignals - PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
        )

        val result = port(gateway = FakeAccessibilityGateway(activeSignals())).inspect(profileUse(withoutLensSignal))

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.CAMERA_STATE_UNKNOWN, unavailable.failure.code)
        assertEquals(
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE.name,
            unavailable.failure.context["missingSignals"],
        )
    }

    @Test
    fun `rear main lens action dispatches only its profile-defined target`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(LENS_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )

        val result = port(gateway = gateway).selectRearMainLens(profileUse())

        assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals("node-$LENS_ACTION_RESOURCE", gateway.clickedNodePath)
    }

    @Test
    fun `speed control opener and speed option are separate semantic dispatches`() = runTest {
        val gateway = FakeAccessibilityGateway(
            nodes = listOf(node(SPEED_CONTROL_ACTION_RESOURCE), node(SPEED_X120_ACTION_RESOURCE)),
            dispatchResult = AccessibilityDispatchResult.SemanticActionDispatched,
        )
        val port = port(gateway = gateway)

        assertInstanceOf(ActionDispatch.Dispatched::class.java, port.openTimeLapseSpeedControl(profileUse()))
        assertEquals(listOf("node-$SPEED_CONTROL_ACTION_RESOURCE"), gateway.clickedNodePaths)

        assertInstanceOf(
            ActionDispatch.Dispatched::class.java,
            port.selectTimeLapseSpeed(TimeLapseSpeed.X120, profileUse()),
        )
        assertEquals(
            listOf("node-$SPEED_CONTROL_ACTION_RESOURCE", "node-$SPEED_X120_ACTION_RESOURCE"),
            gateway.clickedNodePaths,
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
    fun `inspection fails closed when profile lacks picker-open observation signal`() = runTest {
        val withoutPickerSignal = profile().copy(
            stateSignals = profile().stateSignals - PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
        )

        val result = port(gateway = FakeAccessibilityGateway(activeSignals())).inspect(
            profileUse(withoutPickerSignal),
        )

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.CAMERA_STATE_UNKNOWN, unavailable.failure.code)
        assertEquals(
            PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN.name,
            unavailable.failure.context["missingSignals"],
        )
    }

    @Test
    fun `computed probable compatibility requires rehearsal`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val current = environment().copy(androidBuildFingerprint = "different-build")

        val result = port(currentEnvironment = current, gateway = gateway).inspect(profileUse())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL, unavailable.failure.code)
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

    private fun port(
        currentEnvironment: PixelCameraEnvironment = environment(),
        gateway: FakeAccessibilityGateway,
    ): PixelCameraAccessibilityPort = PixelCameraAccessibilityPort(
        cameraLauncher = { error("Camera launch is outside this adapter test") },
        selectorMatcher = SelectorMatcher(),
        environmentProbe = { PortResult.Observed(currentEnvironment) },
        accessibilityGateway = gateway,
    )

    private fun profileUse(
        profile: PixelCameraProfile = profile(),
        kind: ProfileUse.Kind = ProfileUse.Kind.UNATTENDED,
    ): ProfileUse = ProfileUse(profile, kind)

    private fun profile(): PixelCameraProfile {
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
                AutomationAction.SELECT_REAR_MAIN_LENS to selectorSet(LENS_ACTION_RESOURCE),
                AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL to selectorSet(SPEED_CONTROL_ACTION_RESOURCE),
            ),
            speedTargets = mapOf(TimeLapseSpeed.X120 to selectorSet(SPEED_X120_ACTION_RESOURCE)),
            stateSignals = requiredSignals.associateWith { selectorSet(it.name) },
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
        )
    }

    private fun environment(): PixelCameraEnvironment = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        androidSdk = 37,
        androidBuildFingerprint = "google/husky/verified",
        cameraPackage = CAMERA_PACKAGE,
        cameraVersionCode = 700_000_000,
        localeTag = "en-US",
        displayWidthPx = 1_344,
        displayHeightPx = 2_992,
        densityDpi = 480,
    )

    private fun activeSignals(vararg signals: PixelCameraStateSignal): List<UiNodeSnapshot> =
        signals.map { node(it.name) }

    private fun selectorSet(resourceId: String): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            UiSelector(
                packageName = CAMERA_PACKAGE,
                resourceId = resourceId,
                requiresClickable = false,
            ),
        ),
        minimumScore = 100,
    )

    private fun node(resourceId: String): UiNodeSnapshot = UiNodeSnapshot(
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

    private class FakeAccessibilityGateway(
        private val nodes: List<UiNodeSnapshot> = emptyList(),
        private val dispatchResult: AccessibilityDispatchResult = AccessibilityDispatchResult.TargetNotFound,
        private val snapshotResult: AccessibilitySnapshotResult? = null,
    ) : PixelCameraAccessibilityGateway {
        var snapshotCalls: Int = 0
            private set
        var clickedNodePath: String? = null
            private set
        val clickedNodePaths = mutableListOf<String>()

        override suspend fun snapshot(): AccessibilitySnapshotResult {
            snapshotCalls += 1
            return snapshotResult ?: AccessibilitySnapshotResult.Available(nodes = nodes, truncated = false)
        }

        override suspend fun dispatchClick(nodePath: String): AccessibilityDispatchResult {
            clickedNodePath = nodePath
            clickedNodePaths += nodePath
            return dispatchResult
        }
    }

    private companion object {
        const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"
        const val LENS_ACTION_RESOURCE = "profile.lens.rear-main"
        const val SPEED_CONTROL_ACTION_RESOURCE = "profile.speed.open"
        const val SPEED_X120_ACTION_RESOURCE = "profile.speed.x120"
    }
}
