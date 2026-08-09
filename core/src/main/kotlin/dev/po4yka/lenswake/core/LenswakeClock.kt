package dev.po4yka.lenswake.core

import java.time.Clock
import java.time.Instant

fun interface LenswakeClock {
    fun now(): Instant
}

class SystemLenswakeClock(
    private val clock: Clock = Clock.systemUTC(),
) : LenswakeClock {
    override fun now(): Instant = clock.instant()
}
