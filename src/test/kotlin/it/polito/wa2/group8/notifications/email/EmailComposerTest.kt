package it.polito.wa2.group8.notifications.email

import it.polito.wa2.group8.notifications.messaging.dto.EventInfo
import it.polito.wa2.group8.notifications.messaging.dto.IssuedTicket
import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedData
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit test: no Spring, no Docker. EmailComposer is a pure function of its input. */
class EmailComposerTest {

    private val composer = EmailComposer()

    @Test
    fun `composes subject and body from the event data`() {
        val email = composer.composeTicketsIssued(
            TicketsIssuedData(
                orderId = "ord-1",
                customerEmail = " mario@example.com ",
                customerName = "Mario Rossi",
                event = EventInfo(
                    eventTitle = "Concerto X",
                    startsAt = Instant.parse("2026-12-05T19:30:00Z"),
                    venueName = "Teatro Y",
                    venueCity = "Torino",
                ),
                tickets = listOf(
                    IssuedTicket(ticketId = "tkt-1", seatLabel = "E14", sector = "Platea"),
                    IssuedTicket(ticketId = "tkt-2", seatLabel = "E15", sector = "Platea"),
                ),
            ),
        )

        assertEquals("mario@example.com", email.recipient)
        assertEquals("I tuoi biglietti per Concerto X", email.subject)
        assertTrue(email.body.startsWith("Ciao Mario Rossi,"))
        assertTrue("ordine ord-1" in email.body)
        assertTrue("Data: 05/12/2026 20:30" in email.body, "date shown in Italian time")
        assertTrue("Luogo: Teatro Y, Torino" in email.body)
        assertTrue("Biglietti (2):" in email.body)
        assertTrue("- settore Platea, posto E14" in email.body)
    }

    @Test
    fun `works with only the required fields`() {
        val email = composer.composeTicketsIssued(
            TicketsIssuedData(orderId = "ord-2", customerEmail = "anna@example.com"),
        )

        assertEquals("I tuoi biglietti sono pronti", email.subject)
        assertTrue(email.body.startsWith("Ciao,"))
        assertTrue("ordine ord-2" in email.body)
    }

    @Test
    fun `removes line breaks from the subject`() {
        val email = composer.composeTicketsIssued(
            TicketsIssuedData(
                orderId = "ord-3",
                customerEmail = "anna@example.com",
                event = EventInfo(eventTitle = "Concerto\r\nBcc: attacker@example.com"),
            ),
        )

        assertFalse('\n' in email.subject || '\r' in email.subject)
        assertEquals("I tuoi biglietti per Concerto Bcc: attacker@example.com", email.subject)
    }
}
