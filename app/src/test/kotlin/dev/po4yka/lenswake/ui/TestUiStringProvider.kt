package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.R
import java.util.Locale

internal object TestUiStringProvider : UiStringProvider {
    private val values = mapOf(
        R.string.diagnostics_export_title to "Lenswake diagnostics",
        R.string.status_needs_attention to "Needs attention",
        R.string.section_activity to "Activity",
        R.string.action_rehearsal_stopping to "Wait while Lenswake confirms that Pixel Camera has stopped.",
        R.string.action_rehearsal_active_session to
            "Recording session %1\$s owns Pixel Camera. STOP deadline: %2\$s.",
        R.string.action_open_pixel_camera to "Open Pixel Camera",
        R.string.action_open_lenswake to "Open Lenswake",
        R.string.alarm_stop_failure_title to "Scheduled STOP needs manual action",
        R.string.alarm_stop_failure_message to
            "Lenswake could not deliver STOP. Pixel Camera may still be recording; open Pixel Camera and stop it manually.",
        R.string.alarm_start_failure_title to "Scheduled recording did not start",
        R.string.alarm_start_failure_message to
            "Lenswake could not deliver START. The recording may not have started; open Lenswake and review the schedule.",
        R.string.alarm_recovery_failure_title to "Scheduled alarms need attention",
        R.string.alarm_recovery_failure_message to
            "Lenswake could not restore scheduled alarms. Open Lenswake and verify exact-alarm access before relying on schedules.",
        R.string.alarm_journal_failure_title to "Scheduled camera action could not be restored",
        R.string.alarm_journal_failure_message to
            "Lenswake found a corrupt durable alarm entry. An unknown START or STOP could not be restored; Pixel Camera may still be recording. Open Pixel Camera and verify its recording state.",
        R.string.capability_accessibility_service to "Lenswake Accessibility Service",
        R.string.default_schedule_name to "Time Lapse",
        R.string.diagnostics_event_title to "%1\$s",
        R.string.diagnostics_event_detail to "%1\$s - %2\$s",
        R.string.diagnostics_event_operation_detail to "%1\$s - %2\$s - %3\$s",
        R.string.diagnostics_event_failure_detail to "%1\$s - %2\$s - Failure: %3\$s",
        R.string.diagnostics_event_operation_failure_detail to
            "%1\$s - %2\$s - %3\$s - Failure: %4\$s",
        R.string.profile_compatibility_needs_test to "Needs test",
        R.string.profile_compatibility_verified to "Verified for scheduling",
        R.string.preflight_profile_probably_compatible to
            "The closest profile requires a current-device rehearsal.",
        R.string.preflight_profile_needs_rehearsal to
            "The Pixel Camera environment changed; rehearsal is required.",
        R.string.preflight_profile_incompatible to
            "Available profiles are incompatible with the current environment.",
        R.string.preflight_profile_unavailable to
            "No compatible profile is available for the current environment.",
        R.string.active_session_detail to "Session %1\$s · STOP deadline %2\$s",
        R.string.profiles_active_rehearsal_title to "Active test recording",
        R.string.schedules_active_session_title to "Active scheduled recording",
        R.string.schedules_active_session_named_title to "Active recording: %1\$s",
        R.string.status_preparing to "Preparing",
        R.string.status_recording_expected to "Recording expected",
        R.string.status_stop_pending to "STOP pending",
        R.string.status_stop_overdue to "STOP overdue",
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
        R.string.validation_schedule_capture_unsupported to
            "This profile has no verified selectors for the selected mode, speed, and lens.",
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
