package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Reads current device capabilities without mutating system settings or persisted configuration. */
fun interface RuntimePreflightProbe {
    fun inspect(profiles: List<PixelCameraProfile>): PreflightReport

    val invalidations: Flow<Unit>
        get() = emptyFlow()
}
