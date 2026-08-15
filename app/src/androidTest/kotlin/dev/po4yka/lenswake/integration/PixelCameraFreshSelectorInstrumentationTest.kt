package dev.po4yka.lenswake.integration

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.accessibility.validateFreshAccessibilityTarget
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.NormalizedPoint
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelCameraFreshSelectorInstrumentationTest {
    @Test
    fun everyCaptureConfigurationActionResolvesASecondFreshNodeBeforeDispatch() = runBlocking {
        val cases = routingCases()
        val profile = routingProfile(cases)
        cases.forEach { case ->
            val gateway = FreshNodeGateway(case.name, case.resourceId)
            val port = port(profile, gateway)
            val profileUse = ProfileUse(profile, ProfileUse.Kind.UNATTENDED)

            assertTrue(case.dispatch(port, profileUse) is ActionDispatch.Dispatched)
            assertTrue(case.dispatch(port, profileUse) is ActionDispatch.Dispatched)

            assertEquals(case.name, 2, gateway.snapshotCalls)
            assertEquals(
                case.name,
                listOf("${case.name}-snapshot-1", "${case.name}-snapshot-2"),
                gateway.clickedNodes.map(UiNodeSnapshot::id),
            )

            val changedGateway = FreshNodeGateway(
                nodePrefix = case.name,
                resourceId = case.resourceId,
                dispatchResult = AccessibilityDispatchResult.TargetIdentityChanged,
            )
            val changed = case.dispatch(port(profile, changedGateway), profileUse)
            assertTrue(case.name, changed is ActionDispatch.Rejected)
            assertEquals(
                case.name,
                AutomationFailureCode.UI_TARGET_CHANGED,
                (changed as ActionDispatch.Rejected).failure.code,
            )
        }
    }

    @Test
    fun changedFreshAndroidNodeCannotAuthorizeThePreviouslyResolvedNode() {
        val expected = node("root", "instrumentation.identity").copy(
            role = "android.widget.Button",
            contentDescription = "Expected target",
            text = "4K",
            bounds = NormalizedBounds(0f, 0f, 1f, 1f),
        )
        val freshAndroidNode = AccessibilityNodeInfo().apply {
            packageName = CAMERA_PACKAGE
            viewIdResourceName = expected.resourceId
            className = expected.role
            contentDescription = expected.contentDescription
            text = expected.text
            setBoundsInScreen(Rect(0, 0, 100, 100))
            isVisibleToUser = true
            isClickable = true
            isEnabled = true
        }

        assertNull(
            validateFreshAccessibilityTarget(freshAndroidNode, expected, 100, 100),
        )
        freshAndroidNode.contentDescription = "Changed target"
        assertEquals(
            AccessibilityDispatchResult.TargetIdentityChanged,
            validateFreshAccessibilityTarget(freshAndroidNode, expected, 100, 100),
        )
    }

    private fun routingCases(): List<RoutingCase> = buildList {
        add(actionCase("video-mode", AutomationAction.SELECT_VIDEO) { port, use ->
            port.selectVideo(use)
        })
        add(actionCase("time-lapse-mode", AutomationAction.SELECT_TIME_LAPSE) { port, use ->
            port.selectTimeLapse(use)
        })
        add(actionCase("night-sight-time-lapse", AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE) { port, use ->
            port.selectNightSightTimeLapse(use)
        })
        add(
            actionCase(
                "night-sight-start",
                AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
            ) { port, use -> port.startRecording(CaptureMode.NIGHT_SIGHT_TIME_LAPSE, use) },
        )
        add(
            actionCase(
                "night-sight-stop",
                AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
            ) { port, use -> port.stopRecording(CaptureMode.NIGHT_SIGHT_TIME_LAPSE, use) },
        )
        add(actionCase("time-lapse-speed-control", AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL) { port, use ->
            port.openTimeLapseSpeedControl(use)
        })
        add(actionCase("video-resolution-4k", AutomationAction.SELECT_VIDEO_RESOLUTION_4K) { port, use ->
            port.selectVideoResolution4k(use)
        })
        add(actionCase("video-frame-rate-60", AutomationAction.SELECT_VIDEO_FRAME_RATE_60) { port, use ->
            port.selectVideoFrameRate60(use)
        })
        LensSelection.entries.forEach { lens ->
            add(
                actionCase("lens-${lens.name.lowercase()}", lensActions.getValue(lens)) { port, use ->
                    port.selectLens(lens, use)
                },
            )
        }
        TimeLapseSpeed.entries.forEach { speed ->
            add(
                RoutingCase(
                    name = "speed-${speed.name.lowercase()}",
                    resourceId = speedResource(speed),
                    target = RoutingTarget.Speed(speed),
                    dispatch = { port, use -> port.selectTimeLapseSpeed(speed, use) },
                ),
            )
        }
    }

    private fun port(
        profile: PixelCameraProfile,
        gateway: PixelCameraAccessibilityGateway,
    ): PixelCameraAccessibilityPort = PixelCameraAccessibilityPort(
        cameraLauncher = { error("Camera launch is outside selector routing coverage") },
        selectorMatcher = SelectorMatcher(),
        environmentProbe = { PortResult.Observed(profile.environment) },
        accessibilityGateway = gateway,
    )

    private fun actionCase(
        name: String,
        action: AutomationAction,
        dispatch: suspend (PixelCameraAccessibilityPort, ProfileUse) -> ActionDispatch,
    ) = RoutingCase(
        name = name,
        resourceId = actionResource(action),
        target = RoutingTarget.Action(action),
        dispatch = dispatch,
    )

    private fun routingProfile(cases: List<RoutingCase>): PixelCameraProfile {
        val base = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630
        return base.copy(
            targets = cases.mapNotNull { case ->
                (case.target as? RoutingTarget.Action)?.let { it.action to selector(case.resourceId) }
            }.toMap(),
            speedTargets = cases.mapNotNull { case ->
                (case.target as? RoutingTarget.Speed)?.let { it.speed to selector(case.resourceId) }
            }.toMap(),
            fallbackGestures = emptyMap(),
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-12T12:00:00Z"),
        )
    }

    private fun selector(resourceId: String): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            UiSelector(
                packageName = CAMERA_PACKAGE,
                resourceId = resourceId,
            ),
        ),
        minimumScore = 100,
    )

    private fun actionResource(action: AutomationAction): String =
        "instrumentation.action.${action.name.lowercase()}"

    private fun speedResource(speed: TimeLapseSpeed): String =
        "instrumentation.speed.${speed.name.lowercase()}"

    private data class RoutingCase(
        val name: String,
        val resourceId: String,
        val target: RoutingTarget,
        val dispatch: suspend (PixelCameraAccessibilityPort, ProfileUse) -> ActionDispatch,
    )

    private sealed interface RoutingTarget {
        data class Action(val action: AutomationAction) : RoutingTarget

        data class Speed(val speed: TimeLapseSpeed) : RoutingTarget
    }

    private class FreshNodeGateway(
        private val nodePrefix: String,
        private val resourceId: String,
        private val dispatchResult: AccessibilityDispatchResult =
            AccessibilityDispatchResult.SemanticActionDispatched,
    ) : PixelCameraAccessibilityGateway {
        var snapshotCalls: Int = 0
            private set
        val clickedNodes = mutableListOf<UiNodeSnapshot>()

        override suspend fun snapshot(): AccessibilitySnapshotResult {
            snapshotCalls += 1
            return AccessibilitySnapshotResult.Available(
                nodes = listOf(
                    node("$nodePrefix-snapshot-$snapshotCalls", resourceId),
                ),
                truncated = false,
            )
        }

        override suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult {
            clickedNodes += node
            return dispatchResult
        }

        override suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult =
            error("Profile gesture fallback must not be used")

        override suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult =
            error("Global Back is outside selector routing coverage")
    }

    private companion object {
        const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"

        val lensActions = mapOf(
            LensSelection.REAR_MAIN to AutomationAction.SELECT_REAR_MAIN_LENS,
            LensSelection.REAR_ULTRAWIDE to AutomationAction.SELECT_REAR_ULTRAWIDE_LENS,
            LensSelection.REAR_TELEPHOTO to AutomationAction.SELECT_REAR_TELEPHOTO_LENS,
            LensSelection.FRONT to AutomationAction.SELECT_FRONT_LENS,
        )

        fun node(id: String, resourceId: String): UiNodeSnapshot = UiNodeSnapshot(
            id = id,
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
    }
}
