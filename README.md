# Notifications Service

Sends the confirmation email to the customer when the tickets of an order have been issued.

- **Stack:** Kotlin 2.3, Spring Boot 4.1, PostgreSQL 17, RabbitMQ.
- **Input:** the `TicketsIssued` event published by Tickets on RabbitMQ (asynchronous only).
- **Output:** an email to the customer. For now the email is written to the log; a real
  SMTP server (e.g. Mailpit in docker compose) can be plugged in later by configuration only.

## Running the project locally

Requirements: Docker running, JDK 21 (Gradle is downloaded by the wrapper).

1. Start RabbitMQ from the `rabbitMQ` repository (`docker compose up -d` there).
2. In this repository, create the `.env` file from the template and fill in the values.
   `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` must match `rabbitMQ/.env`.

   ```powershell
   Copy-Item .env.example .env
   ```

3. Start the database (PostgreSQL + pgAdmin):

   ```powershell
   docker compose up -d
   ```

4. Start the application (or run `NotificationsApplication` from IntelliJ):

   ```powershell
   .\gradlew.bat bootRun
   ```

   On Linux / macOS: `./gradlew bootRun`. Flyway creates the tables at start-up.

5. Stop the infrastructure with `docker compose down` (add `-v` to delete the data too).

| What | Default address |
|---|---|
| Application | `http://localhost:8082` |
| PostgreSQL | `localhost:5434` |
| pgAdmin | `http://localhost:5052` (server host: `notifications-db`) |

## Messaging

Notifications declares its RabbitMQ objects from code at start-up (`RabbitConfig`):

| Object | Name | Notes |
|---|---|---|
| Exchange (Tickets) | `tickets-events` | `topic`, durable. **Provisional**, to be agreed with Tickets |
| Queue | `notifications.tickets-issued` | bound to `tickets-events` with routing key `tickets.issued` (provisional) |
| Dead letter exchange | `notifications.dlx` | `direct` |
| Dead letter queue | `notifications.tickets-issued.dlq` | messages Notifications gave up on, kept for inspection |

Names can be changed in `application.yaml` (`notifications.messaging.*`).

What happens to a `TicketsIssued` message:

| Case | Result |
|---|---|
| Valid, first delivery | event recorded in `incoming_messages` + email `PENDING` in `email_notifications`, in one transaction |
| Same `eventId` again | recognised as duplicate, nothing created, message acknowledged |
| Different `eventId`, but the order already has its email | event recorded, no second email (one email per order), message acknowledged |
| Not valid JSON, unsupported `type`/`version`, missing or invalid `orderId`/`customerEmail` | rejected, moved to the dead letter queue |
| Temporary error (e.g. database unavailable) | retried in the listener (3 attempts), then moved to the dead letter queue |

### Provisional message format

Until Tickets publishes `TicketsIssued`, it can be published by hand from the RabbitMQ
management UI (`http://localhost:15672`): **Exchanges** → `tickets-events` → **Publish message**,
routing key `tickets.issued`, property `content_type` = `application/json`, payload:

```json
{
  "eventId": "e5b0c1d2-0000-0000-0000-000000000001",
  "type": "TicketsIssued",
  "version": 1,
  "occurredAt": "2026-11-20T18:42:09Z",
  "producer": "tickets",
  "data": {
    "orderId": "ord_01JD8W",
    "customerEmail": "mario@example.com",
    "customerName": "Mario Rossi",
    "event": { "eventTitle": "Concerto X", "startsAt": "2026-12-05T20:30:00Z",
               "venueName": "Teatro Y", "venueCity": "Torino" },
    "tickets": [
      { "ticketId": "tkt_01JD9A", "orderLineId": "ol_1", "seatId": "seat_01JB7Q",
        "seatLabel": "E14", "sectorId": "s1", "sector": "Platea" }
    ]
  }
}
```

Required: `type` (`TicketsIssued`), `version` (`1`), `data.orderId`, `data.customerEmail`, and
`eventId` (or the AMQP `message_id` property). Every other field is optional, and unknown
fields are ignored, so Tickets can add fields without breaking Notifications.

## Tests

```powershell
.\gradlew.bat test
```

The tests start their own PostgreSQL and RabbitMQ with Testcontainers (profile `test`):
Docker must be running, the `.env` file is not needed.

## Database

The schema is created by Flyway (`src/main/resources/db/migration`).

### `incoming_messages`

Events received from the other services. RabbitMQ delivers at least once, so the same event
can arrive twice: the unique constraint on `external_message_id` (the `eventId`) recognises
it. The row is inserted with `INSERT ... ON CONFLICT DO NOTHING`, in the same transaction that
creates the email: either both are stored or neither is.

| Column | Meaning |
|---|---|
| `id` | UUID identifier |
| `external_message_id` | `eventId` of the event, unique |
| `message_type` | e.g. `TicketsIssued` |
| `payload` | original content of the event, JSONB |
| `received_at` | when the event was accepted |

### `email_notifications`

History of the emails and, at the same time, the retry queue. A row is created `PENDING`
when an event is accepted; a scheduled job sends the due `PENDING` rows and marks them
`SENT`, or schedules a new attempt, and marks them `FAILED` after the last one.

One confirmation email per order (`eventi.md`: "una richiesta email per `orderId`"): the
unique index `uq_email_notifications_order` (migration V2) and
`INSERT ... ON CONFLICT (order_id) DO NOTHING` skip a second, different event for the same
order. Together with `incoming_messages`, duplicates are stopped at two levels: the same
event (same `eventId`) and the same order (different `eventId`).

| Column | Meaning |
|---|---|
| `id` | UUID identifier |
| `incoming_message_id` | FK to the event that generated the email |
| `order_id` | order the email refers to |
| `recipient`, `subject`, `body` | the email (lengths as in `EmailRequest` of `notifications.yaml`) |
| `status` | `PENDING`, `SENT`, `FAILED` |
| `attempts` | sending attempts made so far |
| `last_error` | error of the last failed attempt |
| `next_attempt_at` | earliest time of the next attempt |
| `created_at`, `sent_at` | creation and sending time (`sent_at` set only when `SENT`) |
