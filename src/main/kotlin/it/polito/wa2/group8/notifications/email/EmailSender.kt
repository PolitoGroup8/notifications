package it.polito.wa2.group8.notifications.email

import java.util.UUID

/**
 * An email to deliver now.
 *
 * @property id the email_notifications row id. A real sender should use it as Message-ID:
 *              if the same email is ever delivered twice (crash between sending and marking
 *              it SENT), the two copies are recognisable as the same message.
 */
data class OutgoingEmail(
    val id: UUID,
    val recipient: String,
    val subject: String,
    val body: String,
)

/**
 * Delivers an email. The sending job only knows this interface, so the way emails leave
 * the service (log, SMTP/Mailpit, later with attachments) can change without touching it.
 *
 * Contract: return normally if the email was handed over, throw any exception if it was
 * not. The job then records the error and schedules a new attempt.
 */
interface EmailSender {
    fun send(email: OutgoingEmail)
}
