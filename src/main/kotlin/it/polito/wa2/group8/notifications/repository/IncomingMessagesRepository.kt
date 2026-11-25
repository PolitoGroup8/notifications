package it.polito.wa2.group8.notifications.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Access to incoming_messages, the table that records the events already received.
 *
 * Written with JdbcTemplate instead of Spring Data JPA because the key operation is a single
 * PostgreSQL statement (INSERT ... ON CONFLICT DO NOTHING RETURNING) that JPA cannot express.
 * Inside a @Transactional method it uses the same connection and transaction as JPA.
 */
@Repository
class IncomingMessagesRepository(
    private val jdbc: JdbcTemplate,
) {

    /**
     * Records an event if it has never been seen before.
     *
     * @return the id of the new row, or null if an event with the same externalMessageId
     *         already exists (duplicate delivery).
     *
     * ON CONFLICT DO NOTHING turns the duplicate into "no row inserted" instead of an error:
     * in PostgreSQL a failed statement aborts the whole transaction, so catching a
     * unique-constraint exception would leave the transaction unusable.
     * The check is atomic: two concurrent deliveries of the same event cannot both insert.
     */
    fun insertIfNew(externalMessageId: String, messageType: String, payloadJson: String): UUID? =
        jdbc.query(
            """
            INSERT INTO incoming_messages (external_message_id, message_type, payload)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (external_message_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            externalMessageId, messageType, payloadJson,
        ).firstOrNull()

    fun count(): Long =
        jdbc.queryForObject("SELECT count(*) FROM incoming_messages", Long::class.java) ?: 0
}
