package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.SessionId

/** Captures a bounded, non-UI diagnostic snapshot for one persisted execution. */
fun interface EnvironmentSnapshotCollector {
    suspend fun collect(
        snapshotId: EnvironmentSnapshotId,
        sessionId: SessionId,
    ): Result<EnvironmentSnapshot>
}
