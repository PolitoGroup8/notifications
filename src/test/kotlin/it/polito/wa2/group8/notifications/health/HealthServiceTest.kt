package it.polito.wa2.group8.notifications.health

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit test: no Spring, no Docker; the checks are fakes. */
class HealthServiceTest {

    private fun check(name: String, isUp: () -> Boolean) = object : DependencyCheck {
        override val name = name
        override fun isUp() = isUp()
    }

    private fun service(vararg checks: DependencyCheck) =
        HealthService(checks.toList(), Duration.ofMillis(300))

    @Test
    fun `everything up means no failed checks`() {
        service(check("database") { true }, check("broker") { true }).use {
            assertEquals(emptyList(), it.failedChecks())
        }
    }

    @Test
    fun `a check that returns false or throws is reported as failed`() {
        service(
            check("database") { true },
            check("broker") { false },
            check("other") { throw IllegalStateException("connection refused") },
        ).use {
            assertEquals(listOf("broker", "other"), it.failedChecks().sorted())
        }
    }

    @Test
    fun `a check that does not answer is cut by the timeout`() {
        service(check("database") { Thread.sleep(10_000); true }).use {
            val start = System.nanoTime()
            val failed = it.failedChecks()
            val elapsed = Duration.ofNanos(System.nanoTime() - start)

            assertEquals(listOf("database"), failed)
            assertTrue(elapsed < Duration.ofSeconds(2), "answered after $elapsed instead of ~300 ms")
        }
    }
}
