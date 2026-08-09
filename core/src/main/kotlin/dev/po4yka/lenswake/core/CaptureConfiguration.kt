package dev.po4yka.lenswake.core

@JvmInline
value class Zoom private constructor(val factor: Float) {
    companion object {
        fun of(factor: Float): Zoom? = if (factor.isFinite() && factor >= 1f) Zoom(factor) else null
    }
}

sealed interface CaptureConfiguration {
    data class TimeLapse(
        val speed: TimeLapseSpeed,
        val lens: LensSelection = LensSelection.REAR_MAIN,
        val zoom: Zoom? = null,
    ) : CaptureConfiguration
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
