package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationOperation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RetryPolicyTest {
    @Test
    fun `delay grows exponentially and is capped`() {
        val policy = RetryPolicy(
            maxAttempts = 5,
            initialDelay = 100.milliseconds,
            maxDelay = 250.milliseconds,
            multiplier = 2.0,
        )

        assertEquals(100.milliseconds, policy.delayBeforeAttempt(2))
        assertEquals(200.milliseconds, policy.delayBeforeAttempt(3))
        assertEquals(250.milliseconds, policy.delayBeforeAttempt(4))
        assertEquals(250.milliseconds, policy.delayBeforeAttempt(5))
    }

    @Test
    fun `configuration requires a policy for every operation`() {
        val onePolicy = RetryPolicy(
            maxAttempts = 1,
            initialDelay = 0.seconds,
            maxDelay = 0.seconds,
            multiplier = 1.0,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AutomationConfig(
                retryPolicies = mapOf(AutomationOperation.WAKE_DEVICE to onePolicy),
                operationTimeouts = AutomationOperation.entries.associateWith { 1.seconds },
                maxConvergenceSteps = 5,
            )
        }
    }

    @Test
    fun `configuration rejects an unbounded operation timeout`() {
        val oneAttempt = RetryPolicy(
            maxAttempts = 1,
            initialDelay = 0.seconds,
            maxDelay = 0.seconds,
            multiplier = 1.0,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AutomationConfig(
                retryPolicies = AutomationOperation.entries.associateWith { oneAttempt },
                operationTimeouts = AutomationOperation.entries.associateWith { Duration.INFINITE },
                maxConvergenceSteps = 5,
            )
        }
    }

    @Test
    fun `retry policy rejects an unbounded delay`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(
                maxAttempts = 2,
                initialDelay = Duration.INFINITE,
                maxDelay = Duration.INFINITE,
                multiplier = 1.0,
            )
        }
    }
}
