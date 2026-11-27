package it.polito.wa2.group8.notifications.service

import it.polito.wa2.group8.notifications.email.EmailComposer
import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedMessage
import it.polito.wa2.group8.notifications.repository.EmailNotificationsRepository
import it.polito.wa2.group8.notifications.repository.IncomingMessagesRepository
import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Result of handling one delivery. */
enum class HandleOutcome {
    /** New event: a PENDING email was created. */
    ACCEPTED,

    /** The same event (same eventId) was already received: nothing done. */
    DUPLICATE,

    /** A new event, but its order already has an email: event recorded, no second email. */
    DUPLICATE_ORDER,
}

/**
 * The event cannot be processed, now or ever (unsupported type/version, missing or invalid
 * fields). Extends AmqpRejectAndDontRequeueException: the message is not put back in the
 * queue, so the broker moves it to the dead letter queue.
 */
class InvalidEventException(message: String) : AmqpRejectAndDontRequeueException(message)

/**
 * Turns a TicketsIssued event into a PENDING email: once per event, and once per order.
 *
 * Everything happens in ONE database transaction:
 *   1. record the event in incoming_messages (or detect that it was already recorded);
 *   2. create the email_notifications row, PENDING, unless the order already has one
 *      (a different event for the same order: eventi.md, "una richiesta email per orderId").
 * Either both are committed or neither is. If anything fails, the transaction is rolled back,
 * the listener throws, and the broker delivers the message again: since the event was not
 * recorded, it is processed from scratch. If the commit succeeds and the message is then
 * delivered again, step 1 finds it and nothing is created twice.
 *
 * The email itself is NOT sent here: a scheduled job sends the PENDING rows later. A mail
 * server is not part of the database transaction, so sending here would bring back the
 * same "two writes, two systems" problem that the outbox solves.
 */
@Service
class TicketsIssuedHandler(
    private val incomingMessagesRepository: IncomingMessagesRepository,
    private val emailNotificationsRepository: EmailNotificationsRepository,
    private val emailComposer: EmailComposer,
) {

    private val logger = LoggerFactory.getLogger(TicketsIssuedHandler::class.java)

    /**
     * @param event        the event, already converted from JSON.
     * @param rawPayload   the JSON exactly as received, stored in incoming_messages.
     * @param amqpMessageId the AMQP message-id, used as deduplication key if the event has no eventId.
     */
    @Transactional
    fun handle(event: TicketsIssuedMessage, rawPayload: String, amqpMessageId: String?): HandleOutcome {
        validate(event)

        val externalId = event.eventId?.takeIf { it.isNotBlank() }
            ?: amqpMessageId?.takeIf { it.isNotBlank() }
            ?: throw InvalidEventException("TicketsIssued without eventId and without AMQP message-id: cannot deduplicate")

        val incomingId = incomingMessagesRepository.insertIfNew(externalId, event.type, rawPayload)
        if (incomingId == null) {
            logger.info("Duplicate TicketsIssued ignored: eventId={}, orderId={}", externalId, event.data.orderId)
            return HandleOutcome.DUPLICATE
        }

        val email = emailComposer.composeTicketsIssued(event.data)
        val emailId = emailNotificationsRepository.insertIfNoEmailForOrder(
            incomingMessageId = incomingId,
            orderId = event.data.orderId,
            recipient = email.recipient,
            subject = email.subject,
            body = email.body,
        )
        if (emailId == null) {
            // A different event (another eventId) for an order that already has its email.
            // The event stays recorded, so a redelivery of it is recognised by step 1.
            logger.warn(
                "TicketsIssued for an order that already has its email, ignored: eventId={}, orderId={}",
                externalId, event.data.orderId,
            )
            return HandleOutcome.DUPLICATE_ORDER
        }

        logger.info(
            "TicketsIssued accepted: eventId={}, orderId={}, {} ticket(s), email PENDING for {}",
            externalId, event.data.orderId, event.data.tickets.size, email.recipient,
        )
        return HandleOutcome.ACCEPTED
    }

    private fun validate(event: TicketsIssuedMessage) {
        if (event.type != SUPPORTED_TYPE) {
            throw InvalidEventException("Unsupported event type '${event.type}' on the TicketsIssued queue")
        }
        if (event.version != SUPPORTED_VERSION) {
            throw InvalidEventException("Unsupported TicketsIssued version ${event.version} (supported: $SUPPORTED_VERSION)")
        }
        if (event.data.orderId.isBlank()) {
            throw InvalidEventException("TicketsIssued without orderId")
        }
        val email = event.data.customerEmail.trim()
        if (email.length > MAX_EMAIL_LENGTH || !EMAIL.matches(email)) {
            throw InvalidEventException("TicketsIssued with an invalid customerEmail for order ${event.data.orderId}")
        }
    }

    companion object {
        const val SUPPORTED_TYPE = "TicketsIssued"
        const val SUPPORTED_VERSION = 1

        // Same limit as the recipient column (varchar(254)): a longer address is invalid data,
        // rejected here instead of failing on the database insert and being retried.
        const val MAX_EMAIL_LENGTH = 254

        // Deliberately simple: something@something.something, no spaces, local part <= 64 chars.
        private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")
    }
}
