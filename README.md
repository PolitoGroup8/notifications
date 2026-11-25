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
