package it.polito.wa2.group8.notifications.repository

import it.polito.wa2.group8.notifications.db.EmailNotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

/** JPA methods (save, findById, ...) plus the custom insert of EmailNotificationsRepositoryCustom. */
interface EmailNotificationsRepository :
    JpaRepository<EmailNotificationEntity, UUID>,
    EmailNotificationsRepositoryCustom {

    fun findByOrderId(orderId: String): List<EmailNotificationEntity>

    /**
     * The PENDING email that has waited longest among those due at [now], locked for the
     * current transaction; null if none is due.
     *
     * FOR UPDATE locks the row until the transaction ends; SKIP LOCKED makes a concurrent
     * job (e.g. a second instance of Notifications) skip it and take the next one instead of
     * waiting, so the same email is never sent by two jobs at the same time.
     * Served by the partial index idx_email_notifications_due (V1).
     * Native query: FOR UPDATE SKIP LOCKED is PostgreSQL syntax that JPQL does not have.
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT * FROM email_notifications
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """,
    )
    fun findNextDueForUpdate(@Param("now") now: OffsetDateTime): EmailNotificationEntity?
}
