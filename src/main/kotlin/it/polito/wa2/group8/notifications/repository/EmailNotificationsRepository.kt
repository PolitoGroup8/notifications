package it.polito.wa2.group8.notifications.repository

import it.polito.wa2.group8.notifications.db.EmailNotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EmailNotificationsRepository : JpaRepository<EmailNotificationEntity, UUID> {

    fun findByOrderId(orderId: String): List<EmailNotificationEntity>
}
