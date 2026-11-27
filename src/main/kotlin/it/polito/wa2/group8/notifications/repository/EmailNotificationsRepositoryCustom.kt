package it.polito.wa2.group8.notifications.repository

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Operations of EmailNotificationsRepository that Spring Data JPA cannot express.
 *
 * Spring Data "fragment": EmailNotificationsRepository extends this interface, and Spring
 * finds the implementation by name (interface name + "Impl") and plugs it into the
 * repository. Callers see a single repository with both the JPA and the custom methods.
 */
interface EmailNotificationsRepositoryCustom {

    /**
     * Creates the PENDING confirmation email of an order, unless the order already has one.
     *
     * @return the id of the new row, or null if an email for [orderId] already exists.
     *
     * Same technique as IncomingMessagesRepository.insertIfNew: ON CONFLICT DO NOTHING on the
     * unique index uq_email_notifications_order (V2) turns the duplicate into "no row inserted"
     * instead of an error, so the surrounding transaction stays usable. Atomic: two events for
     * the same order handled at the same time cannot both insert.
     * status, attempts, next_attempt_at and created_at take the column defaults (PENDING, 0, now).
     */
    fun insertIfNoEmailForOrder(
        incomingMessageId: UUID,
        orderId: String,
        recipient: String,
        subject: String,
        body: String,
    ): UUID?
}

class EmailNotificationsRepositoryCustomImpl(
    private val jdbc: JdbcTemplate,
) : EmailNotificationsRepositoryCustom {

    override fun insertIfNoEmailForOrder(
        incomingMessageId: UUID,
        orderId: String,
        recipient: String,
        subject: String,
        body: String,
    ): UUID? =
        jdbc.query(
            """
            INSERT INTO email_notifications (incoming_message_id, order_id, recipient, subject, body)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            incomingMessageId, orderId, recipient, subject, body,
        ).firstOrNull()
}
