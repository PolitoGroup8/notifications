package it.polito.wa2.group8.notifications.messaging.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Names of the RabbitMQ objects used by Notifications, read from `notifications.messaging.*`
 * (see application.yaml). The Tickets names are the ones Tickets publishes to (src/rabbitmq.rs).
 *
 * @property ticketsExchange        exchange on which Tickets publishes its events.
 * @property ticketsIssuedRoutingKey routing key of the TicketsIssued events.
 * @property ticketsIssuedQueue     queue of Notifications that receives TicketsIssued.
 * @property deadLetterExchange     exchange receiving the messages Notifications gives up on.
 * @property ticketsIssuedDlq       dead letter queue: rejected TicketsIssued messages, kept for inspection.
 */
@ConfigurationProperties("notifications.messaging")
data class MessagingProperties(
    val ticketsExchange: String = "tickets-events",
    val ticketsIssuedRoutingKey: String = "tickets.issued",
    val ticketsIssuedQueue: String = "notifications.tickets-issued",
    val deadLetterExchange: String = "notifications.dlx",
    val ticketsIssuedDlq: String = "notifications.tickets-issued.dlq",
)
