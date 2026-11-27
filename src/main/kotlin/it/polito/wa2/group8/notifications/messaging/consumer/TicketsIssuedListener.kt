package it.polito.wa2.group8.notifications.messaging.consumer

import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedMessage
import it.polito.wa2.group8.notifications.service.TicketsIssuedHandler
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

/**
 * Entry point of the TicketsIssued events.
 *
 * Kept thin on purpose: it only adapts the AMQP message to the handler. How a failure is
 * treated depends on how this method ends:
 *  - returns normally            -> the message is acknowledged and removed from the queue;
 *  - throws InvalidEventException -> rejected without requeue: it goes to the dead letter queue;
 *  - throws anything else        -> retried a few times (spring.rabbitmq.listener.simple.retry),
 *                                   then rejected and moved to the dead letter queue.
 * A body that is not valid JSON for TicketsIssuedMessage fails before this method is called,
 * during the conversion, and ends in the dead letter queue as well.
 */
@Component
class TicketsIssuedListener(
    private val ticketsIssuedHandler: TicketsIssuedHandler,
) {

    @RabbitListener(queues = ["\${notifications.messaging.tickets-issued-queue}"])
    fun onTicketsIssued(@Payload event: TicketsIssuedMessage, message: Message) {
        ticketsIssuedHandler.handle(
            event = event,
            rawPayload = String(message.body, Charsets.UTF_8),
            amqpMessageId = message.messageProperties.messageId,
        )
    }
}
