package it.polito.wa2.group8.notifications.messaging.consumer

import it.polito.wa2.group8.notifications.TestcontainersConfiguration
import it.polito.wa2.group8.notifications.db.EmailStatus
import it.polito.wa2.group8.notifications.messaging.config.MessagingProperties
import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedData
import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedMessage
import it.polito.wa2.group8.notifications.repository.EmailNotificationsRepository
import it.polito.wa2.group8.notifications.repository.IncomingMessagesRepository
import it.polito.wa2.group8.notifications.service.InvalidEventException
import it.polito.wa2.group8.notifications.service.TicketsIssuedHandler
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.untilNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end test of the consumer against real RabbitMQ and PostgreSQL (Testcontainers):
 * the test plays the role of Tickets and publishes TicketsIssued on the Tickets exchange,
 * exactly as Tickets will do, then checks the database and the dead letter queue.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class TicketsIssuedListenerTest {

    @Autowired private lateinit var rabbitTemplate: RabbitTemplate
    @Autowired private lateinit var amqpAdmin: AmqpAdmin
    @Autowired private lateinit var properties: MessagingProperties
    @Autowired private lateinit var emailNotificationsRepository: EmailNotificationsRepository
    @Autowired private lateinit var incomingMessagesRepository: IncomingMessagesRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var ticketsIssuedHandler: TicketsIssuedHandler

    private val timeout = Duration.ofSeconds(10)

    @BeforeEach
    fun cleanState() {
        jdbc.update("DELETE FROM email_notifications")
        jdbc.update("DELETE FROM incoming_messages")
        amqpAdmin.purgeQueue(properties.ticketsIssuedDlq, false)
    }

    @Test
    fun `a TicketsIssued event creates one PENDING email`() {
        publish(ticketsIssuedJson(eventId = "evt-1", orderId = "ord-1"))

        await atMost timeout untilAsserted {
            assertEquals(1, emailNotificationsRepository.count())
        }
        val email = emailNotificationsRepository.findByOrderId("ord-1").single()
        assertEquals("mario@example.com", email.recipient)
        assertEquals("I tuoi biglietti per Concerto X", email.subject)
        assertTrue("posto E14" in email.body)
        assertEquals(EmailStatus.PENDING, email.status)
        assertEquals(0, email.attempts)
        assertEquals(1, incomingMessagesRepository.count())
    }

    @Test
    fun `the same event delivered twice creates only one email`() {
        val event = ticketsIssuedJson(eventId = "evt-dup", orderId = "ord-dup")
        publish(event)
        publish(event)
        // The listener consumes one message at a time, in order: once this later event is
        // processed, the duplicate has been processed too.
        publish(ticketsIssuedJson(eventId = "evt-after", orderId = "ord-after"))

        await atMost timeout untilAsserted {
            assertEquals(1, emailNotificationsRepository.findByOrderId("ord-after").size)
        }
        assertEquals(1, emailNotificationsRepository.findByOrderId("ord-dup").size)
        assertEquals(2, incomingMessagesRepository.count())
    }

    @Test
    fun `without eventId the AMQP message-id is the deduplication key`() {
        val event = ticketsIssuedJson(eventId = null, orderId = "ord-no-event-id")
        publish(event, amqpMessageId = "amqp-msg-1")
        publish(event, amqpMessageId = "amqp-msg-1")
        publish(ticketsIssuedJson(eventId = "evt-after", orderId = "ord-after"))

        await atMost timeout untilAsserted {
            assertEquals(1, emailNotificationsRepository.findByOrderId("ord-after").size)
        }
        assertEquals(1, emailNotificationsRepository.findByOrderId("ord-no-event-id").size)
    }

    @Test
    fun `a body that is not valid JSON ends in the dead letter queue`() {
        publish("this is not json")

        assertDeadLettered()
    }

    @Test
    fun `an unsupported version ends in the dead letter queue`() {
        publish(ticketsIssuedJson(eventId = "evt-v2", orderId = "ord-v2", version = 2))

        assertDeadLettered()
    }

    @Test
    fun `an invalid customer email ends in the dead letter queue`() {
        publish(ticketsIssuedJson(eventId = "evt-bad-mail", orderId = "ord-bad-mail", email = "not-an-email"))

        assertDeadLettered()
    }

    @Test
    fun `an email longer than the recipient column is rejected as invalid data`() {
        // 64 + 1 + 190 = 255 characters: well-formed, but longer than varchar(254).
        val tooLong = "a".repeat(64) + "@" + "b".repeat(186) + ".com"
        assertEquals(255, tooLong.length)
        val event = TicketsIssuedMessage(
            eventId = "evt-long-mail",
            type = "TicketsIssued",
            version = 1,
            data = TicketsIssuedData(orderId = "ord-long-mail", customerEmail = tooLong),
        )

        // Called directly: it must fail as InvalidEventException (not retried, straight to the
        // DLQ), not as a database error on the insert (retried as if it were temporary).
        assertFailsWith<InvalidEventException> {
            ticketsIssuedHandler.handle(event, rawPayload = "{}", amqpMessageId = null)
        }
        assertEquals(0, incomingMessagesRepository.count())
    }

    /** The rejected message reaches the DLQ, and nothing was written to the database. */
    private fun assertDeadLettered() {
        val dead: Message = await atMost timeout untilNotNull {
            rabbitTemplate.receive(properties.ticketsIssuedDlq, 200)
        }
        assertTrue(dead.body.isNotEmpty())
        assertEquals(0, emailNotificationsRepository.count())
        assertEquals(0, incomingMessagesRepository.count())
    }

    private fun publish(json: String, amqpMessageId: String? = null) {
        val builder = MessageBuilder.withBody(json.toByteArray(Charsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        if (amqpMessageId != null) builder.setMessageId(amqpMessageId)
        rabbitTemplate.send(properties.ticketsExchange, properties.ticketsIssuedRoutingKey, builder.build())
    }

    private fun ticketsIssuedJson(
        eventId: String?,
        orderId: String,
        version: Int = 1,
        email: String = "mario@example.com",
    ): String {
        val eventIdField = if (eventId != null) "\"eventId\": \"$eventId\"," else ""
        return """
            {
              $eventIdField
              "type": "TicketsIssued",
              "version": $version,
              "occurredAt": "2026-11-20T18:42:09Z",
              "producer": "tickets",
              "data": {
                "orderId": "$orderId",
                "customerEmail": "$email",
                "customerName": "Mario Rossi",
                "event": {
                  "eventTitle": "Concerto X",
                  "startsAt": "2026-12-05T19:30:00Z",
                  "venueName": "Teatro Y",
                  "venueCity": "Torino"
                },
                "tickets": [
                  { "ticketId": "tkt-1", "orderLineId": "ol-1", "seatId": "seat-14",
                    "seatLabel": "E14", "sectorId": "s1", "sector": "Platea" }
                ],
                "someFutureField": "ignored by the tolerant reader"
              }
            }
        """.trimIndent()
    }
}
