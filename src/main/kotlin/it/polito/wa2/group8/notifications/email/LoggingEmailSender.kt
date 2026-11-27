package it.polito.wa2.group8.notifications.email

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Development sender: writes the email to the log instead of sending it.
 *
 * Active when notifications.email.sender = log (the default). A real sender (e.g. SMTP to
 * Mailpit) will be another EmailSender enabled by a different value of the same property,
 * so exactly one of them exists at a time.
 *
 * Only for local development: it logs the recipient and the content of the email.
 */
@Component
@ConditionalOnProperty(name = ["notifications.email.sender"], havingValue = "log", matchIfMissing = true)
class LoggingEmailSender : EmailSender {

    private val logger = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun send(email: OutgoingEmail) {
        logger.info(
            "EMAIL (not really sent: log sender)\nMessage-ID: {}\nTo: {}\nSubject: {}\n\n{}",
            email.id, email.recipient, email.subject, email.body,
        )
    }
}
