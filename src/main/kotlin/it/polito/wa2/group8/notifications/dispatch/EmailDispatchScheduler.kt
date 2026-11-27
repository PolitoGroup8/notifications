package it.polito.wa2.group8.notifications.dispatch

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Enables @Scheduled and the binding of EmailDispatchProperties. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(EmailDispatchProperties::class)
class EmailDispatchConfig

/**
 * Runs the sending job periodically.
 *
 * A separate class from EmailDispatcher on purpose: @Transactional works through a Spring
 * proxy, so dispatchNext() must be called from another bean to open its transaction.
 *
 * fixedDelay: the next run starts poll-interval-ms after the previous one has FINISHED, so two
 * runs never overlap. Each run sends at most batch-size emails, the rest waits for the next run.
 * Can be switched off (notifications.email.dispatch.scheduler.enabled=false), as in the tests,
 * which call the job directly.
 */
@Component
@ConditionalOnProperty(
    name = ["notifications.email.dispatch.scheduler.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class EmailDispatchScheduler(
    private val emailDispatcher: EmailDispatcher,
    private val properties: EmailDispatchProperties,
) {

    private val logger = LoggerFactory.getLogger(EmailDispatchScheduler::class.java)

    @Scheduled(fixedDelayString = "\${notifications.email.dispatch.scheduler.poll-interval-ms:5000}")
    fun run() {
        val handled = runOnce()
        if (handled > 0) logger.debug("Sending job: {} email(s) handled", handled)
    }

    /** Handles up to batch-size due emails. @return how many were handled. */
    fun runOnce(): Int {
        var handled = 0
        while (handled < properties.batchSize && emailDispatcher.dispatchNext() != null) {
            handled++
        }
        return handled
    }
}
