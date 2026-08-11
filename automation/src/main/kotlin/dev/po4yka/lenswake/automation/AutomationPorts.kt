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

    data class TimeLapseSpeedPicker(
        val speed: TimeLapseSpeed? = null,
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

/**
 * A persisted Pixel Camera profile together with the execution policy that may consume it.
 *
 * Keeping the policy beside the profile prevents infrastructure adapters from accidentally
 * treating a rehearsal candidate as safe for unattended automation.
 */
data class ProfileUse(
    val profile: PixelCameraProfile,
    val kind: Kind,
) {
    enum class Kind {
        UNATTENDED,
        REHEARSAL,
    }
}

interface PixelCameraPort {
    suspend fun inspect(profileUse: ProfileUse): PortResult<PixelCameraState>

    suspend fun launchSecureCamera(profileUse: ProfileUse): ActionDispatch

    suspend fun selectVideo(profileUse: ProfileUse): ActionDispatch

    suspend fun selectTimeLapse(profileUse: ProfileUse): ActionDispatch

    suspend fun openTimeLapseSpeedControl(profileUse: ProfileUse): ActionDispatch

    suspend fun selectTimeLapseSpeed(
        speed: TimeLapseSpeed,
        profileUse: ProfileUse,
    ): ActionDispatch

    suspend fun closeTimeLapseSpeedControl(
        expectedSpeed: TimeLapseSpeed?,
        profileUse: ProfileUse,
    ): ActionDispatch

    suspend fun selectRearMainLens(profileUse: ProfileUse): ActionDispatch

    suspend fun startRecording(profileUse: ProfileUse): ActionDispatch

    suspend fun stopRecording(profileUse: ProfileUse): ActionDispatch
}

data class RecordingMediaBaseline(
    val generation: Long,
    val version: String,
) {
    init {
        require(generation >= 0) { "MediaStore generation must not be negative" }
        require(version.isNotBlank()) { "MediaStore version must not be blank" }
    }
}

data class SavedRecordingEvidence(
    val generationAdded: Long,
    val sizeBytes: Long,
    val durationMillis: Long,
) {
    init {
        require(generationAdded >= 0) { "Saved media generation must not be negative" }
        require(sizeBytes > 0) { "Saved media size must be positive" }
        require(durationMillis > 0) { "Saved media duration must be positive" }
    }
}

/** System boundary for correlating a Lenswake session with Pixel Camera-owned MediaStore output. */
interface RecordingMediaPort {
    suspend fun captureBaseline(): PortResult<RecordingMediaBaseline>

    suspend fun findSavedRecording(
        baseline: RecordingMediaBaseline,
    ): PortResult<SavedRecordingEvidence?>
}

fun interface AutomationSleeper {
    suspend fun sleep(duration: Duration)
}

object CoroutineAutomationSleeper : AutomationSleeper {
    override suspend fun sleep(duration: Duration) {
        delay(duration)
    }
}
