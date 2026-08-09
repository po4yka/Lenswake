package dev.po4yka.lenswake.core

import java.util.UUID

@JvmInline
value class ScheduleId(val value: String) {
    init {
        require(value.isNotBlank()) { "Schedule identifier must not be blank" }
    }

    companion object {
        fun new(): ScheduleId = ScheduleId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "Profile identifier must not be blank" }
    }

    companion object {
        fun new(): ProfileId = ProfileId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Session identifier must not be blank" }
    }

    companion object {
        fun new(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class EventId(val value: String) {
    init {
        require(value.isNotBlank()) { "Event identifier must not be blank" }
    }

    companion object {
        fun new(): EventId = EventId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class EnvironmentSnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "Environment snapshot identifier must not be blank" }
    }

    companion object {
        fun new(): EnvironmentSnapshotId = EnvironmentSnapshotId(UUID.randomUUID().toString())
    }
}
