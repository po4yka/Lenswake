package dev.po4yka.lenswake.core

import java.time.Duration

data class RehearsalRequest(
    val profileId: ProfileId,
    val capture: CaptureConfiguration.TimeLapse,
    val recordingDuration: Duration,
) {
    init {
        require(recordingDuration >= MIN_RECORDING_DURATION && recordingDuration <= MAX_RECORDING_DURATION) {
            "Rehearsal recording duration must be between 5 and 30 seconds inclusive"
        }
        require(recordingDuration.nano % NANOS_PER_MILLISECOND == 0) {
            "Rehearsal recording duration must use millisecond precision"
        }
    }

    companion object {
        val MIN_RECORDING_DURATION: Duration = Duration.ofSeconds(5)
        val MAX_RECORDING_DURATION: Duration = Duration.ofSeconds(30)

        private const val NANOS_PER_MILLISECOND: Int = 1_000_000
    }
}
