package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode

internal data class CaptureModeSelectionFailures(
    val dispatch: AutomationFailureCode,
    val verification: AutomationFailureCode,
)

internal val CaptureMode.selectionFailureCodes: CaptureModeSelectionFailures
    get() = when (this) {
        CaptureMode.VIDEO -> CaptureModeSelectionFailures(
            dispatch = AutomationFailureCode.VIDEO_MODE_NOT_FOUND,
            verification = AutomationFailureCode.VIDEO_MODE_NOT_VERIFIED,
        )
        CaptureMode.TIME_LAPSE -> CaptureModeSelectionFailures(
            dispatch = AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND,
            verification = AutomationFailureCode.TIME_LAPSE_MODE_NOT_VERIFIED,
        )
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> CaptureModeSelectionFailures(
            dispatch = AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
            verification = AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_VERIFIED,
        )
    }
