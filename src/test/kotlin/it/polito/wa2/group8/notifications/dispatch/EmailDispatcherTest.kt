package it.polito.wa2.group8.notifications.dispatch

import it.polito.wa2.group8.notifications.TestcontainersConfiguration
import it.polito.wa2.group8.notifications.db.EmailStatus
import it.polito.wa2.group8.notifications.email.EmailSender
import it.polito.wa2.group8.notifications.email.OutgoingEmail
import it.polito.wa2.group8.notifications.repository.EmailNotificationsRepository
import it.polito.wa2.group8.notifications.repository.IncomingMessagesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sending job against the real PostgreSQL. The scheduler is off in the test profile:
 * the tests call EmailDispatcher directly, and a recording sender replaces the log sender.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, EmailDispatcherTest.RecordingSenderConfig::class)
class EmailDispatcherTest {

    /** Records the emails it receives; fails with [failure] when it is set. */
    class RecordingEmailSender : EmailSender {
        val sent: MutableList<OutgoingEmail> = Collections.synchronizedList(mutableListOf())

        @Volatile var failure: Exception? = null

        override fun send(email: OutgoingEmail) {
            failure?.let { throw it }
            sent += email
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingSenderConfig {
        @Bean
        @Primary
        fun recordingEmailSender() = RecordingEmailSender()
    }

    @Autowired private lateinit var dispatcher: EmailDispatcher
    @Autowired private lateinit var sender: RecordingEmailSender
    @Autowired private lateinit var properties: EmailDispatchProperties
    @Autowired private lateinit var emailNotificationsRepository: EmailNotificationsRepository
    @Autowired private lateinit var incomingMessagesRepository: IncomingMessagesRepository
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun cleanState() {
        jdbc.update("DELETE FROM email_notifications")
        jdbc.update("DELETE FROM incoming_messages")
        sender.sent.clear()
        sender.failure = null
    }

    @Test
    fun `a due PENDING email is sent and marked SENT`() {
        val id = pendingEmail("ord-1")

        assertEquals(DispatchOutcome.SENT, dispatcher.dispatchNext())

        val email = emailNotificationsRepository.findById(id).get()
        assertEquals(EmailStatus.SENT, email.status)
        assertEquals(1, email.attempts)
        assertNotNull(email.sentAt)
        assertNull(email.lastError)
        assertEquals(listOf(id), sender.sent.map { it.id })
        assertEquals("mario@example.com", sender.sent.single().recipient)
    }

    @Test
    fun `a failed attempt keeps the email PENDING and schedules the next one later`() {
        val id = pendingEmail("ord-2")
        sender.failure = IllegalStateException("mail server down")

        val before = OffsetDateTime.now()
        assertEquals(DispatchOutcome.RETRY_SCHEDULED, dispatcher.dispatchNext())

        val email = emailNotificationsRepository.findById(id).get()
        assertEquals(EmailStatus.PENDING, email.status)
        assertEquals(1, email.attempts)
        assertEquals("IllegalStateException: mail server down", email.lastError)
        assertNull(email.sentAt)
        // first wait: initial-backoff (30 s by default)
        assertTrue(email.nextAttemptAt >= before.plus(properties.initialBackoff).minusSeconds(1))
        // not due any more: the job leaves it alone until then
        assertNull(dispatcher.dispatchNext())
    }

    @Test
    fun `the last failed attempt marks the email FAILED`() {
        val id = pendingEmail("ord-3")
        // one attempt left
        jdbc.update("UPDATE email_notifications SET attempts = ? WHERE id = ?", properties.maxAttempts - 1, id)
        sender.failure = IllegalStateException("mailbox does not exist")

        assertEquals(DispatchOutcome.FAILED, dispatcher.dispatchNext())

        val email = emailNotificationsRepository.findById(id).get()
        assertEquals(EmailStatus.FAILED, email.status)
        assertEquals(properties.maxAttempts, email.attempts)
        assertEquals("IllegalStateException: mailbox does not exist", email.lastError)
        assertNull(dispatcher.dispatchNext(), "a FAILED email is never picked again")
    }

    @Test
    fun `an email not yet due is left alone`() {
        val id = pendingEmail("ord-4")
        jdbc.update(
            "UPDATE email_notifications SET next_attempt_at = now() + interval '10 minutes' WHERE id = ?",
            id,
        )

        assertNull(dispatcher.dispatchNext())
        assertTrue(sender.sent.isEmpty())
        assertEquals(EmailStatus.PENDING, emailNotificationsRepository.findById(id).get().status)
    }

    @Test
    fun `a run sends at most batch-size emails, oldest first`() {
        val first = pendingEmail("ord-a", dueSince = Duration.ofMinutes(3))
        val second = pendingEmail("ord-b", dueSince = Duration.ofMinutes(2))
        pendingEmail("ord-c", dueSince = Duration.ofMinutes(1))
        val scheduler = EmailDispatchScheduler(dispatcher, properties.copy(batchSize = 2))

        assertEquals(2, scheduler.runOnce())
        assertEquals(listOf(first, second), sender.sent.map { it.id })

        assertEquals(1, scheduler.runOnce())
        assertEquals(0, scheduler.runOnce())
    }

    @Test
    fun `an email locked by another job is skipped, not sent twice`() {
        val lockedByOther = pendingEmail("ord-locked", dueSince = Duration.ofMinutes(2))
        val free = pendingEmail("ord-free", dueSince = Duration.ofMinutes(1))

        // Another job (another thread, another transaction) is in the middle of sending the
        // oldest email: it holds the row lock until its transaction ends.
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherJob = thread {
            transactionTemplate.executeWithoutResult {
                val row = emailNotificationsRepository.findNextDueForUpdate(OffsetDateTime.now())
                assertEquals(lockedByOther, row?.id)
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(locked.await(10, TimeUnit.SECONDS))

        // This job does not wait for the lock: it skips that row and takes the next one.
        assertEquals(DispatchOutcome.SENT, dispatcher.dispatchNext())
        assertEquals(listOf(free), sender.sent.map { it.id })
        assertNull(dispatcher.dispatchNext(), "the only other due email is locked")

        release.countDown()
        otherJob.join()
    }

    /** Inserts a PENDING email for [orderId], due since [dueSince]; returns its id. */
    private fun pendingEmail(orderId: String, dueSince: Duration = Duration.ofSeconds(1)): UUID {
        val incomingId = incomingMessagesRepository.insertIfNew("evt-$orderId", "TICKETS_ISSUED", "{}")!!
        val id = emailNotificationsRepository.insertIfNoEmailForOrder(
            incomingMessageId = incomingId,
            orderId = orderId,
            recipient = "mario@example.com",
            subject = "I tuoi biglietti per Concerto X",
            body = "Ciao Mario",
        )!!
        jdbc.update(
            "UPDATE email_notifications SET next_attempt_at = now() - (? * interval '1 second') WHERE id = ?",
            dueSince.seconds, id,
        )
        return id
    }
}
