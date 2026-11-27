package it.polito.wa2.group8.notifications.dispatch

import org.springframework.stereotype.Component
import java.time.Duration

/**
 * When to try again after a failed sending attempt, and when to give up.
 *
 * Exponential backoff: with the defaults (30 s, x2, max 30 min, 5 attempts) the waits after
 * the 1st, 2nd, 3rd and 4th failure are 30 s, 1 min, 2 min and 4 min; the 5th failure marks
 * the email FAILED. Growing waits avoid hammering a mail server that is down, while a short
 * outage is still covered by the first retries.
 *
 * Pure logic, no I/O: unit tested without Spring.
 */
@Component
class RetryPolicy(
    private val properties: EmailDispatchProperties,
) {

    /** True if, after [attemptsMade] failed attempts, no further attempt must be made. */
    fun isExhausted(attemptsMade: Int): Boolean = attemptsMade >= properties.maxAttempts

    /** Wait before the next attempt, after [attemptsMade] (>= 1) failed attempts. */
    fun delayAfter(attemptsMade: Int): Duration {
        require(attemptsMade >= 1) { "attemptsMade must be at least 1" }
        val factor = Math.pow(properties.multiplier, (attemptsMade - 1).toDouble())
        val millis = properties.initialBackoff.toMillis() * factor
        val capped = minOf(millis, properties.maxBackoff.toMillis().toDouble())
        return Duration.ofMillis(capped.toLong())
    }
}
