package it.polito.wa2.group8.notifications.repository

import it.polito.wa2.group8.notifications.db.EmailNotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** JPA methods (save, findById, ...) plus the custom insert of EmailNotificationsRepositoryCustom. */
interface EmailNotificationsRepository :
    JpaRepository<EmailNotificationEntity, UUID>,
    EmailNotificationsRepositoryCustom {

    fun findByOrderId(orderId: String): List<EmailNotificationEntity>
}
