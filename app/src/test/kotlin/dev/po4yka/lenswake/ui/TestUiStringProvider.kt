package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.R
import java.util.Locale

internal object TestUiStringProvider : UiStringProvider {
    private val values = mapOf(
        R.string.action_diagnostics_not_implemented to "Diagnostic export is not implemented yet.",
        R.string.action_rehearsal_stopping to "Wait while Lenswake confirms that Pixel Camera has stopped.",
        R.string.capability_accessibility_service to "Lenswake Accessibility Service",
        R.string.default_schedule_name to "Time Lapse",
        R.string.profile_compatibility_needs_test to "Needs test",
        R.string.profile_compatibility_verified to "Verified for scheduling",
        R.string.profile_installed to "Camera profile installed. Test recording is required before scheduling.",
        R.string.profile_storage_issue_title to "Camera profile storage issue",
        R.string.profile_storage_issue_detail to
            "Stored profile entry “%1\$s” is corrupt and was excluded. Lenswake cannot repair this entry.",
        R.string.profile_unsupported_environment to
            "No camera profile is available for %1\$s with this Pixel Camera version and language.",
        R.string.status_enabled to "Enabled",
        R.string.validation_schedule_name_required to "Add a name so you can recognize this schedule.",
        R.string.validation_schedule_times_required to "Choose a start and end date and time.",
        R.string.validation_schedule_dst_gap to
            "Choose a different time; this time is affected by a daylight-saving clock change.",
        R.string.validation_schedule_end_after_start to "End must be after start.",
        R.string.validation_schedule_start_future to
            "Start must be in the future while the schedule is active.",
        R.string.validation_schedule_profile_required to "Choose a camera setup verified for scheduling.",
    )

    override fun get(
        resourceId: Int,
        vararg formatArgs: Any,
    ): String {
        val template = values[resourceId] ?: "resource-$resourceId"
        return if (formatArgs.isEmpty()) template else String.format(Locale.US, template, *formatArgs)
    }

    override fun quantity(
        resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = get(resourceId, *formatArgs)
}
