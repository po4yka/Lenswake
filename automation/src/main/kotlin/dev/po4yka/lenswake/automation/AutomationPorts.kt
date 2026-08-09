package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.TimeLapseSpeed
import kotlinx.coroutines.delay
import kotlin.time.Duration

data class DeviceState(
    val interactive: Boolean,
)

sealed interface PixelCameraState {
    data object NotRunning : PixelCameraState

    data object Unknown : PixelCameraState

    data object Photo : PixelCameraState

    data class Video(
        val recording: Boolean,
    ) : PixelCameraState

    data class TimeLapse(
        val speed: TimeLapseSpeed?,
        val recording: Boolean,
        val lens: LensSelection? = null,
    ) : PixelCameraState

    data object RecordingUnknownMode : PixelCameraState
}

sealed interface PortResult<out T> {
    data class Observed<T>(
        val value: T,
    ) : PortResult<T>

    data class Unavailable(
        val failure: AutomationFailure,
    ) : PortResult<Nothing>
}

sealed interface ActionDispatch {
    data class Dispatched(
        val method: InteractionMethod,
    ) : ActionDispatch

    data class Rejected(
        val failure: AutomationFailure,
    ) : ActionDispatch
}

interface DeviceControlPort {
    suspend fun inspect(): PortResult<DeviceState>

    suspend fun wake(): ActionDispatch
}

interface PixelCameraPort {
    suspend fun inspect(profile: PixelCameraProfile): PortResult<PixelCameraState>

    suspend fun launchSecureCamera(profile: PixelCameraProfile): ActionDispatch

    suspend fun selectVideo(profile: PixelCameraProfile): ActionDispatch

    suspend fun selectTimeLapse(profile: PixelCameraProfile): ActionDispatch

    suspend fun selectTimeLapseSpeed(
        speed: TimeLapseSpeed,
        profile: PixelCameraProfile,
    ): ActionDispatch

    suspend fun selectRearMainLens(profile: PixelCameraProfile): ActionDispatch

    suspend fun startRecording(profile: PixelCameraProfile): ActionDispatch

    suspend fun stopRecording(profile: PixelCameraProfile): ActionDispatch
}

fun interface AutomationSleeper {
    suspend fun sleep(duration: Duration)
}

object CoroutineAutomationSleeper : AutomationSleeper {
    override suspend fun sleep(duration: Duration) {
        delay(duration)
    }
}
