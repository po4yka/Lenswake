package dev.po4yka.lenswake.core

@JvmInline
value class Zoom private constructor(val factor: Float) {
    companion object {
        fun of(factor: Float): Zoom? = if (factor.isFinite() && factor >= 1f) Zoom(factor) else null
    }
}

sealed interface CaptureConfiguration {
    val mode: CaptureMode
    val timeLapseSpeed: TimeLapseSpeed?
    val lens: LensSelection
    val zoom: Zoom?

    data class Video(
        override val lens: LensSelection = LensSelection.REAR_MAIN,
        override val zoom: Zoom? = null,
        val resolution: VideoResolution = VideoResolution.UHD_4K,
        val frameRate: VideoFrameRate = VideoFrameRate.FPS_60,
    ) : CaptureConfiguration {
        override val mode: CaptureMode = CaptureMode.VIDEO
        override val timeLapseSpeed: TimeLapseSpeed? = null
    }

    data class TimeLapse(
        val speed: TimeLapseSpeed,
        override val lens: LensSelection = LensSelection.REAR_MAIN,
        override val zoom: Zoom? = null,
    ) : CaptureConfiguration {
        override val mode: CaptureMode = CaptureMode.TIME_LAPSE
        override val timeLapseSpeed: TimeLapseSpeed = speed
    }

    data class NightSightTimeLapse(
        override val lens: LensSelection = LensSelection.REAR_MAIN,
        override val zoom: Zoom? = null,
    ) : CaptureConfiguration {
        override val mode: CaptureMode = CaptureMode.NIGHT_SIGHT_TIME_LAPSE
        override val timeLapseSpeed: TimeLapseSpeed? = null
    }
}

enum class VideoResolution {
    UHD_4K,
    LEGACY_UNKNOWN,
}

enum class VideoFrameRate {
    FPS_60,
    LEGACY_UNKNOWN,
}

enum class CaptureMode {
    VIDEO,
    TIME_LAPSE,
    NIGHT_SIGHT_TIME_LAPSE,
}

enum class TimeLapseSpeed {
    AUTO,
    X5,
    X10,
    X30,
    X120,
}

enum class LensSelection {
    REAR_MAIN,
    REAR_ULTRAWIDE,
    REAR_TELEPHOTO,
    FRONT,
}
