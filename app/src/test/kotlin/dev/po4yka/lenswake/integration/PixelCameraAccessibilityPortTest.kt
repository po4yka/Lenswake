package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
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

        val result = port(gateway = gateway).inspect(profile())

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
    fun `time lapse lens remains unknown when configured rear main signal does not match`() = runTest {
        val gateway = FakeAccessibilityGateway(
            activeSignals(
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
        )

        val result = port(gateway = gateway).inspect(profile())

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

        val result = port(gateway = FakeAccessibilityGateway(activeSignals())).inspect(withoutLensSignal)

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

        val result = port(gateway = gateway).selectRearMainLens(profile())

        assertInstanceOf(ActionDispatch.Dispatched::class.java, result)
        assertEquals("node-$LENS_ACTION_RESOURCE", gateway.clickedNodePath)
    }

    @Test
    fun `computed probable compatibility requires rehearsal`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val current = environment().copy(androidBuildFingerprint = "different-build")

        val result = port(currentEnvironment = current, gateway = gateway).inspect(profile())

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL, unavailable.failure.code)
        assertEquals(0, gateway.snapshotCalls)
    }

    @Test
    fun `stored probable compatibility requires rehearsal even on exact environment`() = runTest {
        val gateway = FakeAccessibilityGateway(activeSignals())
        val probablyCompatible = profile().copy(compatibility = ProfileCompatibility.PROBABLY_COMPATIBLE)

        val result = port(gateway = gateway).inspect(probablyCompatible)

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

        val result = port(gateway = gateway).inspect(unsupported)

        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, unavailable.failure.code)
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

    private fun profile(): PixelCameraProfile {
        val requiredSignals = setOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
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
            ),
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
        private val nodes: List<UiNodeSnapshot>,
        private val dispatchResult: AccessibilityDispatchResult = AccessibilityDispatchResult.TargetNotFound,
    ) : PixelCameraAccessibilityGateway {
        var snapshotCalls: Int = 0
            private set
        var clickedNodePath: String? = null
            private set

        override suspend fun snapshot(): AccessibilitySnapshotResult {
            snapshotCalls += 1
            return AccessibilitySnapshotResult.Available(nodes = nodes, truncated = false)
        }

        override suspend fun dispatchClick(nodePath: String): AccessibilityDispatchResult {
            clickedNodePath = nodePath
            return dispatchResult
        }
    }

    private companion object {
        const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"
        const val LENS_ACTION_RESOURCE = "profile.lens.rear-main"
    }
}
