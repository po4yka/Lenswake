package dev.po4yka.lenswake.data.internal.mapping

import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.Zoom
import dev.po4yka.lenswake.core.VideoFrameRate
import dev.po4yka.lenswake.core.VideoResolution

private const val CAPTURE_VIDEO = "VIDEO"
private const val CAPTURE_TIME_LAPSE = "TIME_LAPSE"
private const val CAPTURE_NIGHT_SIGHT_TIME_LAPSE = "NIGHT_SIGHT_TIME_LAPSE"
private const val CAPTURE_SPEED_NONE = ""

internal data class CaptureColumns(
    val type: String,
    val speed: String,
    val lens: String,
    val zoom: Float?,
    val videoResolution: String,
    val videoFrameRate: String,
)

internal fun CaptureConfiguration.toColumns(): CaptureColumns = when (this) {
    is CaptureConfiguration.Video -> CaptureColumns(
        type = CAPTURE_VIDEO,
        speed = CAPTURE_SPEED_NONE,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = resolution.name,
        videoFrameRate = frameRate.name,
    )
    is CaptureConfiguration.TimeLapse -> CaptureColumns(
        type = CAPTURE_TIME_LAPSE,
        speed = speed.name,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = VideoResolution.UHD_4K.name,
        videoFrameRate = VideoFrameRate.FPS_60.name,
    )
    is CaptureConfiguration.NightSightTimeLapse -> CaptureColumns(
        type = CAPTURE_NIGHT_SIGHT_TIME_LAPSE,
        speed = CAPTURE_SPEED_NONE,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = VideoResolution.UHD_4K.name,
        videoFrameRate = VideoFrameRate.FPS_60.name,
    )
}

internal fun captureFromColumns(
    type: String,
    speed: String,
    lens: String,
    zoom: Float?,
    videoResolution: String = VideoResolution.UHD_4K.name,
    videoFrameRate: String = VideoFrameRate.FPS_60.name,
): CaptureConfiguration {
    val persistedLens = enumValueOf<LensSelection>(lens)
    val persistedZoom = zoom?.let {
        requireNotNull(Zoom.of(it)) { "Invalid persisted zoom factor: $it" }
    }
    return when (type) {
        CAPTURE_VIDEO -> {
            require(speed == CAPTURE_SPEED_NONE) { "Video capture must not persist a Time Lapse speed" }
            CaptureConfiguration.Video(
                lens = persistedLens,
                zoom = persistedZoom,
                resolution = enumValueOf<VideoResolution>(videoResolution),
                frameRate = enumValueOf<VideoFrameRate>(videoFrameRate),
            )
        }
        CAPTURE_TIME_LAPSE -> CaptureConfiguration.TimeLapse(
            speed = enumValueOf<TimeLapseSpeed>(speed),
            lens = persistedLens,
            zoom = persistedZoom,
        )
        CAPTURE_NIGHT_SIGHT_TIME_LAPSE -> {
            require(speed == CAPTURE_SPEED_NONE) {
                "Night Sight Time Lapse must not persist a standard speed"
            }
            CaptureConfiguration.NightSightTimeLapse(persistedLens, persistedZoom)
        }
        else -> error("Unsupported persisted capture type: $type")
    }
}
