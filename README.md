# Notifications Service

Sends the confirmation email to the customer when the tickets of an order have been issued.

- **Stack:** Kotlin 2.3, Spring Boot 4.1, PostgreSQL 17, RabbitMQ.
- **Input:** the `TICKETS_ISSUED` event published by Tickets on RabbitMQ (asynchronous only).
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

## Health

`GET /api/v1/health` (no authentication, as in `notifications.yaml`): checks that the database
answers and that the broker consumer is ready (RabbitMQ connection open and `TICKETS_ISSUED`
listener running). Each check has a time limit (`notifications.health.check-timeout`, 2 s).

| Result | Response |
|---|---|
| Ready | `200` `{"status":"UP"}` |
| Database or broker not ready | `503` RFC 9457 problem (`application/problem+json`), `code: not-ready` |

Both responses carry `Cache-Control: no-store`.

```powershell
curl.exe -i http://localhost:8082/api/v1/health
```

## Messaging

Notifications declares its RabbitMQ objects from code at start-up (`RabbitConfig`):

| Object | Name | Notes |
|---|---|---|
| Exchange (Tickets) | `tickets-events` | `topic`, durable; Tickets publishes `TICKETS_ISSUED` here |
| Queue | `notifications.tickets-issued` | bound to `tickets-events` with routing key `tickets.issued` |
| Dead letter exchange | `notifications.dlx` | `direct` |
| Dead letter queue | `notifications.tickets-issued.dlq` | messages Notifications gave up on, kept for inspection |

Names can be changed in `application.yaml` (`notifications.messaging.*`).

What happens to a `TICKETS_ISSUED` message:

| Case | Result |
|---|---|
| Valid, first delivery | event recorded in `incoming_messages` + email `PENDING` in `email_notifications`, in one transaction |
| Same `eventId` again | recognised as duplicate, nothing created, message acknowledged |
| Different `eventId`, but the order already has its email | event recorded, no second email (one email per order), message acknowledged |
| Not valid JSON, unsupported `type`/`version`, missing or invalid `orderId`/`customerEmail` | rejected, moved to the dead letter queue |
| Temporary error (e.g. database unavailable) | retried in the listener (3 attempts), then moved to the dead letter queue |

### Message format

The event as published by Tickets (Tickets repository: `src/events.rs`, `RABBIT_MQ.md`).
Without Tickets running, it can be published by hand from the RabbitMQ management UI
(`http://localhost:15672`): **Exchanges** → `tickets-events` → **Publish message**,
routing key `tickets.issued`, property `content_type` = `application/json`, payload:

```json
{
  "eventId": "85fb487d-28ce-4e47-a525-236fa564895a",
  "type": "TICKETS_ISSUED",
  "version": 1,
  "producer": "tickets",
  "occurredAt": "2026-11-25T17:00:00Z",
  "data": {
    "orderId": "ordine-1",
    "customerEmail": "customer@example.test",
    "customerName": "Mario Rossi",
    "event": { "eventTitle": "Concerto", "startsAt": "2026-12-10T20:00:00Z",
               "venueName": "Teatro", "venueCity": "Torino" },
    "tickets": [
      { "ticketId": "b63fe671-a440-4f05-afbf-585dcc28a388",
        "qrObjectKey": "tickets/b63fe671-a440-4f05-afbf-585dcc28a388/qr.png",
        "seatLabel": "E14", "sector": "platea" }
    ]
  }
}
```

Required: `type` (`TICKETS_ISSUED`), `version` (`1`), `data.orderId`, `data.customerEmail`, and
`eventId` (or the AMQP `message_id` property). Every other field is optional (Tickets sends
`customerName` only when known), and unknown fields are ignored, so Tickets can add fields
without breaking Notifications.

`qrObjectKey` is read but not used yet: the email is written to the log, so the QR images
(in the private RustFS bucket of Tickets) are not attached.

## Sending job

A scheduled job (`EmailDispatchScheduler` → `EmailDispatcher`) sends the `PENDING` emails
whose `next_attempt_at` has passed, oldest first, **one email per transaction**:

1. lock the next due email with `SELECT ... FOR UPDATE SKIP LOCKED` (a second instance of
   Notifications skips it and takes another one, so no email is sent by two jobs at once);
2. hand it to the `EmailSender`;
3. success → `SENT` with `sent_at`; failure → `attempts + 1`, `last_error`, and
   `next_attempt_at` moved later (30 s, 1 min, 2 min, 4 min); after the 5th failed attempt → `FAILED`;
4. commit.

For now the sender writes the email to the log (`notifications.email.sender: log`); an SMTP
sender (Mailpit) will be selected with the same property.

The mail server is not part of the database transaction: if the service stops after sending
an email and before the commit, that email is sent again at restart (at-least-once delivery).
Committing after every email limits this to one email; the row id is passed to the sender to
be used as `Message-ID`, so the two copies are recognisable as the same message.

| Property (`notifications.email.dispatch.*`) | Default | Meaning |
|---|---|---|
| `scheduler.enabled` | `true` | runs the job (off in the tests, which call it directly) |
| `scheduler.poll-interval-ms` | `5000` | pause between the end of a run and the next one |
| `batch-size` | `20` | emails sent at most per run |
| `max-attempts` | `5` | attempts per email, the first one included |
| `initial-backoff`, `multiplier`, `max-backoff` | `30s`, `2`, `30m` | waits between attempts |

## Tests

```powershell
.\gradlew.bat test
```

The tests start their own PostgreSQL and RabbitMQ with Testcontainers (profile `test`):
Docker must be running, the `.env` file is not needed.

### Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `dev` / `main`, on every
pull request towards them, and by hand from the **Actions** tab (*Run workflow*):

1. `./gradlew build`: compiles and runs all the tests, Testcontainers included (the GitHub
   Linux runner has Docker);
2. `docker build`: checks that the image used by docker compose still builds.

If a test fails, the HTML test report is attached to the run as the `test-report` artifact.

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
| `message_type` | e.g. `TICKETS_ISSUED` |
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
