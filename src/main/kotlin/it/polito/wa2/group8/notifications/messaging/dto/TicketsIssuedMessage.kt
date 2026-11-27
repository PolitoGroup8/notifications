package it.polito.wa2.group8.notifications.messaging.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * The TicketsIssued event published by Tickets when all the tickets of an order are issued.
 *
 * PROVISIONAL FORMAT: envelope of eventi.md (M2) + the data available in Tickets
 * (NewTicket and the order snapshot). To be aligned with Tickets when they publish it.
 *
 * {
 *   "eventId": "e5b0c1d2-...",
 *   "type": "TicketsIssued",
 *   "version": 1,
 *   "occurredAt": "2026-11-20T18:42:09Z",
 *   "producer": "tickets",
 *   "data": {
 *     "orderId": "ord_01JD8W",
 *     "customerEmail": "mario@example.com",
 *     "customerName": "Mario Rossi",
 *     "event": { "eventTitle": "Concerto X", "startsAt": "2026-12-05T20:30:00Z",
 *                "venueName": "Teatro Y", "venueCity": "Torino" },
 *     "tickets": [
 *       { "ticketId": "tkt_01JD9A", "orderLineId": "ol_1", "seatId": "seat_01JB7Q",
 *         "seatLabel": "E14", "sectorId": "s1", "sector": "Platea" }
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class IssuedTicket(
    val ticketId: String? = null,
    val orderLineId: String? = null,
    val seatId: String? = null,
    val seatLabel: String? = null,
    val sectorId: String? = null,
    val sector: String? = null,
)
