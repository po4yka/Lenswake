package dev.po4yka.lenswake.data.internal.mapping

import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.Zoom
import dev.po4yka.lenswake.core.VideoFrameRate
import dev.po4yka.lenswake.core.VideoResolution
import dev.po4yka.lenswake.core.VideoSettings

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
        type = CaptureMode.VIDEO.name,
        speed = CAPTURE_SPEED_NONE,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = resolution.name,
        videoFrameRate = frameRate.name,
    )
    is CaptureConfiguration.TimeLapse -> CaptureColumns(
        type = CaptureMode.TIME_LAPSE.name,
        speed = speed.name,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = VideoResolution.LEGACY_UNKNOWN.name,
        videoFrameRate = VideoFrameRate.LEGACY_UNKNOWN.name,
    )
    is CaptureConfiguration.NightSightTimeLapse -> CaptureColumns(
        type = CaptureMode.NIGHT_SIGHT_TIME_LAPSE.name,
        speed = CAPTURE_SPEED_NONE,
        lens = lens.name,
        zoom = zoom?.factor,
        videoResolution = VideoResolution.LEGACY_UNKNOWN.name,
        videoFrameRate = VideoFrameRate.LEGACY_UNKNOWN.name,
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
        CaptureMode.VIDEO.name -> {
            require(speed == CAPTURE_SPEED_NONE) { "Video capture must not persist a Time Lapse speed" }
            CaptureConfiguration.Video(
                lens = persistedLens,
                zoom = persistedZoom,
                resolution = enumValueOf<VideoResolution>(videoResolution),
                frameRate = enumValueOf<VideoFrameRate>(videoFrameRate),
            )
        }
        CaptureMode.TIME_LAPSE.name -> CaptureConfiguration.TimeLapse(
            speed = enumValueOf<TimeLapseSpeed>(speed),
            lens = persistedLens,
            zoom = persistedZoom,
        )
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE.name -> {
            require(speed == CAPTURE_SPEED_NONE) {
                "Night Sight Time Lapse must not persist a standard speed"
            }
            CaptureConfiguration.NightSightTimeLapse(persistedLens, persistedZoom)
        }
        else -> error("Unsupported persisted capture type: $type")
    }
}

internal fun videoSettingsFromNullableColumns(
    resolution: String?,
    frameRate: String?,
): VideoSettings? = when {
    resolution == null && frameRate == null -> null
    resolution != null && frameRate != null -> VideoSettings(
        resolution = enumValueOf<VideoResolution>(resolution),
        frameRate = enumValueOf<VideoFrameRate>(frameRate),
    )
    else -> error("Persisted video settings must contain both resolution and frame rate")
}
