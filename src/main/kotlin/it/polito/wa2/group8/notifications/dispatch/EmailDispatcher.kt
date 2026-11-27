package it.polito.wa2.group8.notifications.dispatch

import it.polito.wa2.group8.notifications.db.EmailStatus
import it.polito.wa2.group8.notifications.email.EmailSender
import it.polito.wa2.group8.notifications.email.OutgoingEmail
import it.polito.wa2.group8.notifications.repository.EmailNotificationsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** What happened to the email handled by one call of [EmailDispatcher.dispatchNext]. */
enum class DispatchOutcome {
    /** Handed over to the sender: SENT. */
    SENT,

    /** The attempt failed: still PENDING, next attempt later. */
    RETRY_SCHEDULED,

    /** The last attempt failed: FAILED, no more attempts. */
    FAILED,
}

/**
 * Sends the PENDING emails, ONE PER TRANSACTION.
 *
 * Each call: lock the next due email (FOR UPDATE SKIP LOCKED), send it, record the result,
 * commit. The changes to the entity are saved at commit by JPA dirty checking, no save() needed.
 *
 * Why one email per transaction and not the whole batch in one: a mail server is not part of
 * the database transaction. If the service stops after sending and before the commit, the row
 * is still PENDING and that email is sent again at restart (at-least-once delivery). Committing
 * after every email limits this to a single email, instead of every email of the batch.
 *
 * A sending error is caught and recorded, so the transaction still commits: the row keeps
 * the new attempt count, the error and the time of the next attempt.
 */
@Service
class EmailDispatcher(
    private val emailNotificationsRepository: EmailNotificationsRepository,
    private val emailSender: EmailSender,
    private val retryPolicy: RetryPolicy,
) {

    private val logger = LoggerFactory.getLogger(EmailDispatcher::class.java)

    /** Sends the next due email, if any. @return what happened to it, or null if none was due. */
    @Transactional
    fun dispatchNext(): DispatchOutcome? {
        val now = OffsetDateTime.now()
        val email = emailNotificationsRepository.findNextDueForUpdate(now) ?: return null

        email.attempts += 1
        return try {
            emailSender.send(OutgoingEmail(email.id, email.recipient, email.subject, email.body))
            email.status = EmailStatus.SENT
            email.sentAt = OffsetDateTime.now()
            email.lastError = null
            logger.info("Email {} sent (order {}, attempt {})", email.id, email.orderId, email.attempts)
            DispatchOutcome.SENT
        } catch (e: Exception) {
            email.lastError = describe(e)
            if (retryPolicy.isExhausted(email.attempts)) {
                email.status = EmailStatus.FAILED
                logger.error(
                    "Email {} FAILED after {} attempts (order {}): {}",
                    email.id, email.attempts, email.orderId, email.lastError,
                )
                DispatchOutcome.FAILED
            } else {
                val delay = retryPolicy.delayAfter(email.attempts)
                email.nextAttemptAt = now.plus(delay)
                logger.warn(
                    "Email {} not sent (attempt {}), next attempt in {}: {}",
                    email.id, email.attempts, delay, email.lastError,
                )
                DispatchOutcome.RETRY_SCHEDULED
            }
        }
    }

    /** Short description of the error for last_error: exception type and message, bounded. */
    private fun describe(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(MAX_ERROR_LENGTH)

    companion object {
        const val MAX_ERROR_LENGTH = 2000
    }
}
