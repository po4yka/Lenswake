package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationOperation
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class RetryPolicy(
    val maxAttempts: Int,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val multiplier: Double,
) {
    init {
        require(maxAttempts > 0) { "Retry policy must allow at least one attempt" }
        require(!initialDelay.isNegative()) { "Initial retry delay must not be negative" }
        require(maxDelay >= initialDelay) { "Maximum retry delay must not be less than the initial delay" }
        require(multiplier.isFinite() && multiplier >= 1.0) { "Retry multiplier must be finite and at least one" }
    }

    fun delayBeforeAttempt(attempt: Int): Duration {
        require(attempt in 2..maxAttempts) { "Attempt must be between two and maxAttempts" }
        val scaled = initialDelay * multiplier.pow(attempt - 2)
        return minOf(scaled, maxDelay)
    }
}

data class AutomationConfig(
    val retryPolicies: Map<AutomationOperation, RetryPolicy>,
    val maxConvergenceSteps: Int,
) {
    init {
        require(retryPolicies.keys == AutomationOperation.entries.toSet()) {
            "Automation configuration must define exactly one policy for every operation"
        }
        require(maxConvergenceSteps > 0) { "Maximum convergence steps must be positive" }
    }

    fun policyFor(operation: AutomationOperation): RetryPolicy =
        requireNotNull(retryPolicies[operation]) { "Missing retry policy for $operation" }

    companion object {
        fun production(): AutomationConfig {
            val interaction = RetryPolicy(3, 200.milliseconds, 1.seconds, 2.0)
            val inspection = RetryPolicy(8, 250.milliseconds, 1.seconds, 1.5)
            return AutomationConfig(
                retryPolicies = AutomationOperation.entries.associateWith { operation ->
                    when (operation) {
                        AutomationOperation.WAKE_DEVICE -> RetryPolicy(3, 250.milliseconds, 1.seconds, 2.0)
                        AutomationOperation.LAUNCH_CAMERA -> RetryPolicy(3, 500.milliseconds, 2.seconds, 2.0)
                        AutomationOperation.INSPECT_CAMERA,
                        AutomationOperation.VERIFY_RECORDING,
                        AutomationOperation.VERIFY_STOPPED,
                        -> inspection
                        else -> interaction
                    }
                },
                maxConvergenceSteps = 12,
            )
        }
    }
}
