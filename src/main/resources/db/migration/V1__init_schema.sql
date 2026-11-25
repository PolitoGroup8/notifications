-- =====================================================
-- INCOMING MESSAGES
-- =====================================================
-- Events received from the other services (e.g. TicketsIssued from Tickets).
-- The broker delivers at least once, so the same event can arrive more than once:
-- the unique constraint on external_message_id lets the consumer recognise it.
-- The row is inserted with INSERT ... ON CONFLICT DO NOTHING in the same transaction
-- that creates the email_notifications row: either both exist or neither does.
-- external_message_id is the eventId of the event (or the AMQP message-id): text,
-- because its format is decided by the producer.

CREATE TABLE incoming_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    external_message_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,

    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_incoming_messages_external_message_id
        UNIQUE (external_message_id)
);


-- =====================================================
-- EMAIL NOTIFICATIONS
-- =====================================================
-- History of the emails to send and sent, and at the same time the retry queue.
-- A row is created PENDING when an event is accepted; a scheduled job sends the
-- PENDING rows whose next_attempt_at has passed, then marks them SENT, or schedules
-- a new attempt (attempts + 1, next_attempt_at later) and FAILED after the last one.
-- Lengths of recipient, subject and body follow EmailRequest in notifications.yaml.

CREATE TABLE email_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    incoming_message_id UUID NOT NULL,
    order_id VARCHAR(255),

    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body VARCHAR(10000) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,

    CONSTRAINT fk_email_notifications_incoming_message
        FOREIGN KEY (incoming_message_id)
            REFERENCES incoming_messages(id),

    CONSTRAINT chk_email_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    CONSTRAINT chk_email_notifications_attempts
        CHECK (attempts >= 0),

    CONSTRAINT chk_email_notifications_sent_at
        CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);


-- =====================================================
-- INDEXES
-- =====================================================

-- The sending job reads only the PENDING rows that are due: a partial index keeps
-- this query fast however many SENT rows accumulate in the history.
CREATE INDEX idx_email_notifications_due
    ON email_notifications (next_attempt_at)
    WHERE status = 'PENDING';

-- History lookups by order (e.g. "which emails were sent for order X?").
CREATE INDEX idx_email_notifications_order
    ON email_notifications (order_id);
