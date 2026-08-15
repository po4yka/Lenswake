package dev.po4yka.lenswake.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.TimeLapseSpeed

@Composable
internal fun CaptureMode.label(): String = stringResource(
    when (this) {
        CaptureMode.VIDEO -> R.string.capture_mode_video
        CaptureMode.TIME_LAPSE -> R.string.capture_mode_time_lapse
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> R.string.capture_mode_night_sight_time_lapse
    },
)

@Composable
internal fun TimeLapseSpeed.label(): String = stringResource(
    when (this) {
        TimeLapseSpeed.AUTO -> R.string.time_lapse_speed_auto
        TimeLapseSpeed.X5 -> R.string.time_lapse_speed_x5
        TimeLapseSpeed.X10 -> R.string.time_lapse_speed_x10
        TimeLapseSpeed.X30 -> R.string.time_lapse_speed_x30
        TimeLapseSpeed.X120 -> R.string.time_lapse_speed_x120
    },
)

@Composable
internal fun LensSelection.label(): String = stringResource(
    when (this) {
        LensSelection.REAR_MAIN -> R.string.lens_rear_main
        LensSelection.REAR_ULTRAWIDE -> R.string.lens_rear_ultrawide
        LensSelection.REAR_TELEPHOTO -> R.string.lens_rear_telephoto
        LensSelection.FRONT -> R.string.lens_front
    },
)

@Composable
internal fun CaptureConfiguration.label(): String = when (this) {
    is CaptureConfiguration.TimeLapse -> stringResource(
        R.string.schedule_time_lapse_capture_summary,
        mode.label(),
        speed.label(),
        lens.label(),
    )
    is CaptureConfiguration.Video -> stringResource(
        R.string.schedule_video_capture_summary,
        mode.label(),
        stringResource(R.string.video_resolution_4k),
        stringResource(R.string.video_frame_rate_60),
        lens.label(),
    )
    is CaptureConfiguration.NightSightTimeLapse ->
        stringResource(R.string.schedule_capture_summary, mode.label(), lens.label())
}
