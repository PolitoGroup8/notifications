package it.polito.wa2.group8.notifications.dispatch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration of the sending job, read from `notifications.email.dispatch.*`.
 *
 * @property batchSize      emails sent at most per run of the job (the others wait for the next run).
 * @property maxAttempts    sending attempts per email, the first one included; then the email is FAILED.
 * @property initialBackoff wait after the first failed attempt.
 * @property multiplier     each further wait is the previous one times this factor.
 * @property maxBackoff     upper limit of a single wait.
 *
 * The scheduler is switched on/off by `notifications.email.dispatch.scheduler.enabled` and runs every
 * `notifications.email.dispatch.scheduler.poll-interval-ms` (see EmailDispatchScheduler).
 */
@ConfigurationProperties("notifications.email.dispatch")
data class EmailDispatchProperties(
    val batchSize: Int = 20,
    val maxAttempts: Int = 5,
    val initialBackoff: Duration = Duration.ofSeconds(30),
    val multiplier: Double = 2.0,
    val maxBackoff: Duration = Duration.ofMinutes(30),
) {
    init {
        require(batchSize >= 1) { "notifications.email.dispatch.batch-size must be at least 1" }
        require(maxAttempts >= 1) { "notifications.email.dispatch.max-attempts must be at least 1" }
        require(multiplier >= 1.0) { "notifications.email.dispatch.multiplier must be at least 1" }
    }
}
