package it.polito.wa2.group8.notifications.dispatch

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit test: no Spring, no Docker. */
class RetryPolicyTest {

    private val policy = RetryPolicy(
        EmailDispatchProperties(
            maxAttempts = 5,
            initialBackoff = Duration.ofSeconds(30),
            multiplier = 2.0,
            maxBackoff = Duration.ofMinutes(3),
        ),
    )

    @Test
    fun `waits grow exponentially up to the maximum`() {
        assertEquals(Duration.ofSeconds(30), policy.delayAfter(1))
        assertEquals(Duration.ofMinutes(1), policy.delayAfter(2))
        assertEquals(Duration.ofMinutes(2), policy.delayAfter(3))
        // 4 minutes would exceed the 3-minute maximum
        assertEquals(Duration.ofMinutes(3), policy.delayAfter(4))
    }

    @Test
    fun `gives up after the configured number of attempts`() {
        assertFalse(policy.isExhausted(4))
        assertTrue(policy.isExhausted(5))
    }
}
