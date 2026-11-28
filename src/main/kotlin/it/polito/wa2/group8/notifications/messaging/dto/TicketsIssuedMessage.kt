package it.polito.wa2.group8.notifications.messaging.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * The TICKETS_ISSUED event published by Tickets when all the tickets of an order are issued
 * (Tickets repository, src/events.rs and RABBIT_MQ.md):
 *
 * {
 *   "eventId": "85fb487d-28ce-4e47-a525-236fa564895a",
 *   "type": "TICKETS_ISSUED",
 *   "version": 1,
 *   "producer": "tickets",
 *   "occurredAt": "2026-11-25T17:00:00Z",
 *   "data": {
 *     "orderId": "ordine-1",
 *     "customerEmail": "customer@example.test",
 *     "customerName": "Mario Rossi",                 (only when known)
 *     "event": { "eventTitle": "Concerto", "startsAt": "2026-12-10T20:00:00Z",
 *                "venueName": "Teatro", "venueCity": "Torino" },
 *     "tickets": [
 *       { "ticketId": "b63fe671-...", "qrObjectKey": "tickets/b63fe671-.../qr.png",
 *         "seatLabel": "E14", "sector": "platea" }
 *     ]
 *   }
 * }
 *
 * Tolerant reader: unknown fields are ignored (a producer can add fields without breaking us),
 * and only what is needed to send the email is required (type, version, orderId, customerEmail).
 * Everything else is nullable or has a default.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketsIssuedMessage(
    /** Unique id of the event: deduplication key. If missing, the AMQP message-id is used. */
    val eventId: String? = null,
    val type: String,
    val version: Int,
    val occurredAt: Instant? = null,
    val producer: String? = null,
    val data: TicketsIssuedData,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketsIssuedData(
    val orderId: String,
    val customerEmail: String,
    val customerName: String? = null,
    val event: EventInfo? = null,
    val tickets: List<IssuedTicket> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EventInfo(
    val eventTitle: String? = null,
    val startsAt: Instant? = null,
    val venueName: String? = null,
    val venueCity: String? = null,
)

/**
 * One issued ticket. Only what the email uses is read.
 *
 * @property qrObjectKey key of the QR image in the private RustFS bucket of Tickets
 *                       (tickets/<ticketId>/qr.png). Not used yet: the email is written to the
 *                       log, so there is nothing to attach; kept for the attachments.
 * @property sector      name of the sector (e.g. "platea").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class IssuedTicket(
    val ticketId: String? = null,
    val qrObjectKey: String? = null,
    val seatLabel: String? = null,
    val sector: String? = null,
)
