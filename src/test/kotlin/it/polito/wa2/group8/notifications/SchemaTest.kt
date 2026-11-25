package it.polito.wa2.group8.notifications

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Checks, on the real PostgreSQL, the two mechanisms the V1 schema relies on:
 * duplicate detection on incoming_messages and the consistency of email_notifications.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class SchemaTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun cleanTables() {
        jdbc.update("DELETE FROM email_notifications")
        jdbc.update("DELETE FROM incoming_messages")
    }

    @Test
    fun `the same event is recorded only once`() {
        val insertIfNew = """
            INSERT INTO incoming_messages (external_message_id, message_type, payload)
            VALUES (?, 'TicketsIssued', '{}'::jsonb)
            ON CONFLICT (external_message_id) DO NOTHING
        """.trimIndent()

        // First delivery: one row inserted. Redelivery: zero rows, and no error,
        // so the transaction is still usable (unlike a plain INSERT that violates the constraint).
        assertEquals(1, jdbc.update(insertIfNew, "evt-1"))
        assertEquals(0, jdbc.update(insertIfNew, "evt-1"))

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM incoming_messages", Int::class.java))
    }

    @Test
    fun `a new email notification starts PENDING with no attempts`() {
        val incomingId = insertIncoming("evt-2")

        jdbc.update(
            """
            INSERT INTO email_notifications (incoming_message_id, order_id, recipient, subject, body)
            VALUES (?, 'ord-1', 'mario@example.com', 'Biglietti disponibili', 'I tuoi biglietti sono pronti')
            """.trimIndent(),
            incomingId,
        )

        val row = jdbc.queryForMap("SELECT status, attempts, sent_at FROM email_notifications")
        assertEquals("PENDING", row["status"])
        assertEquals(0, row["attempts"])
        assertEquals(null, row["sent_at"])
    }

    @Test
    fun `an email cannot be SENT without a sending time`() {
        val incomingId = insertIncoming("evt-3")

        assertThrows<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO email_notifications (incoming_message_id, recipient, subject, body, status)
                VALUES (?, 'mario@example.com', 'subject', 'body', 'SENT')
                """.trimIndent(),
                incomingId,
            )
        }
    }

    private fun insertIncoming(externalId: String): UUID =
        jdbc.queryForObject(
            """
            INSERT INTO incoming_messages (external_message_id, message_type, payload)
            VALUES (?, 'TicketsIssued', '{}'::jsonb)
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            externalId,
        )!!
}
