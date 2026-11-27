-- =====================================================
-- ONE CONFIRMATION EMAIL PER ORDER
-- =====================================================
-- eventi.md (M2), TicketsIssued: "eventId; una richiesta email per orderId".
-- incoming_messages already rejects the same event delivered twice (same eventId).
-- This index also rejects a second, different event for the same order (another
-- eventId, e.g. a bug in the producer): the consumer inserts the email with
-- INSERT ... ON CONFLICT (order_id) DO NOTHING, so the second one is simply skipped.
--
-- Today every row is a ticket confirmation. If other kinds of email are added later
-- (e.g. a refund), the index will have to include the kind of email as well.
--
-- order_id is nullable: rows without an order are not constrained (NULLs are distinct).

CREATE UNIQUE INDEX uq_email_notifications_order
    ON email_notifications (order_id);

-- The unique index also serves the lookups by order: the plain index is redundant.
DROP INDEX idx_email_notifications_order;
