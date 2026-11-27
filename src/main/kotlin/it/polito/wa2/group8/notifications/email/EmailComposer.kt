package it.polito.wa2.group8.notifications.email

import it.polito.wa2.group8.notifications.messaging.dto.TicketsIssuedData
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An email ready to be stored: recipient, subject and plain-text body. */
data class ComposedEmail(
    val recipient: String,
    val subject: String,
    val body: String,
)

/**
 * Builds the confirmation email from the data of a TicketsIssued event.
 *
 * Pure function of its input (no I/O): easy to unit test and to change.
 * No entry credential or QR code is put in the email (D009): it points the customer
 * to the personal area, where the tickets are shown after login.
 */
@Component
class EmailComposer {

    fun composeTicketsIssued(data: TicketsIssuedData): ComposedEmail {
        val title = data.event?.eventTitle?.takeIf { it.isNotBlank() }
        val subject = if (title != null) "I tuoi biglietti per $title" else "I tuoi biglietti sono pronti"

        val body = buildString {
            appendLine(if (data.customerName.isNullOrBlank()) "Ciao," else "Ciao ${data.customerName},")
            appendLine()
            appendLine("i biglietti del tuo ordine ${data.orderId} sono stati emessi.")
            appendLine()

            data.event?.let { event ->
                event.eventTitle?.let { appendLine("Evento: $it") }
                event.startsAt?.let { appendLine("Data: ${DATE_FORMAT.format(it)}") }
                val place = listOfNotNull(event.venueName, event.venueCity).filter { it.isNotBlank() }
                if (place.isNotEmpty()) appendLine("Luogo: ${place.joinToString(", ")}")
                appendLine()
            }

            if (data.tickets.isNotEmpty()) {
                appendLine("Biglietti (${data.tickets.size}):")
                data.tickets.forEach { ticket ->
                    val seat = listOfNotNull(
                        ticket.sector?.let { "settore $it" },
                        ticket.seatLabel?.let { "posto $it" },
                    ).joinToString(", ").ifEmpty { ticket.ticketId ?: "biglietto" }
                    appendLine("- $seat")
                }
                appendLine()
            }

            appendLine("Puoi consultare i tuoi biglietti nella tua area personale.")
        }

        return ComposedEmail(
            recipient = data.customerEmail.trim(),
            subject = singleLine(subject).take(MAX_SUBJECT_LENGTH),
            body = body.take(MAX_BODY_LENGTH),
        )
    }

    /**
     * Removes line breaks: a CR/LF in an email header (here the subject) would let a crafted
     * value inject extra headers. notifications.yaml forbids them too (pattern ^[^\r\n]+$).
     */
    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim()

    companion object {
        // Same limits as the email_notifications columns and EmailRequest in notifications.yaml.
        const val MAX_SUBJECT_LENGTH = 200
        const val MAX_BODY_LENGTH = 10_000

        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Europe/Rome"))
    }
}
