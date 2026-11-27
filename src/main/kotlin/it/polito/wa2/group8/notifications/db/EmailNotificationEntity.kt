package it.polito.wa2.group8.notifications.db

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/** Lifecycle of an email: PENDING until sent (SENT) or until the attempts run out (FAILED). */
enum class EmailStatus { PENDING, SENT, FAILED }

/**
 * A row of email_notifications: the email to send (or sent) and its sending state.
 * The table is created by Flyway (V1__init_schema.sql); with ddl-auto=validate Hibernate
 * checks at start-up that this mapping matches it.
 */
@Entity
@Table(name = "email_notifications")
class EmailNotificationEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    /** The incoming_messages row (the event) this email comes from. */
    @Column(name = "incoming_message_id", nullable = false, updatable = false)
    var incomingMessageId: UUID,

    @Column(name = "order_id", length = 255, updatable = false)
    var orderId: String?,

    @Column(name = "recipient", nullable = false, length = 254, updatable = false)
    var recipient: String,

    @Column(name = "subject", nullable = false, length = 200, updatable = false)
    var subject: String,

    @Column(name = "body", nullable = false, length = 10000, updatable = false)
    var body: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: EmailStatus = EmailStatus.PENDING,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "sent_at")
    var sentAt: OffsetDateTime? = null,
)
