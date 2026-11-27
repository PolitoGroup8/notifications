package it.polito.wa2.group8.notifications.messaging.config

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/**
 * RabbitMQ topology of Notifications, declared from code: Spring's RabbitAdmin creates these
 * objects on the broker at the first connection, if they do not exist yet.
 *
 *   tickets-events (topic) --tickets.issued--> notifications.tickets-issued --> listener
 *                                                      |
 *                                         rejected (x-dead-letter-exchange)
 *                                                      v
 *   notifications.dlx (direct) ------------> notifications.tickets-issued.dlq
 *
 * As a consumer, Notifications owns its queue and its binding. It also declares the Tickets
 * exchange so that the binding can be created even before Tickets runs: the declaration is
 * idempotent, as long as Tickets declares it with the same type (topic) and durability.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties::class)
class RabbitConfig(
    private val properties: MessagingProperties,
) {

    /**
     * JSON <-> Kotlin objects for @RabbitListener parameters, using the JsonMapper configured
     * by Spring Boot (with the Kotlin module). Without it, a listener could only receive
     * String or ByteArray.
     */
    @Bean
    fun messageConverter(jsonMapper: JsonMapper): MessageConverter =
        JacksonJsonMessageConverter(jsonMapper)

    // ---- Tickets -> Notifications ----

    @Bean
    fun ticketsExchange(): TopicExchange =
        TopicExchange(properties.ticketsExchange, true, false)

    /**
     * Durable queue (RabbitMQ 4 refuses transient non-exclusive queues). A message that the
     * listener rejects without requeue is moved by the broker to the dead letter exchange,
     * instead of being deleted.
     */
    @Bean
    fun ticketsIssuedQueue(): Queue =
        QueueBuilder.durable(properties.ticketsIssuedQueue)
            .deadLetterExchange(properties.deadLetterExchange)
            .deadLetterRoutingKey(properties.ticketsIssuedDlq)
            .build()

    @Bean
    fun ticketsIssuedBinding(): Binding =
        BindingBuilder.bind(ticketsIssuedQueue())
            .to(ticketsExchange())
            .with(properties.ticketsIssuedRoutingKey)

    // ---- Dead letters ----

    @Bean
    fun deadLetterExchange(): DirectExchange =
        DirectExchange(properties.deadLetterExchange, true, false)

    @Bean
    fun ticketsIssuedDlq(): Queue =
        QueueBuilder.durable(properties.ticketsIssuedDlq).build()

    @Bean
    fun ticketsIssuedDlqBinding(): Binding =
        BindingBuilder.bind(ticketsIssuedDlq())
            .to(deadLetterExchange())
            .with(properties.ticketsIssuedDlq)
}
