package it.polito.wa2.group8.notifications

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * The application starts against real PostgreSQL and RabbitMQ: Flyway applies the
 * migrations and Hibernate validates the entities against the resulting schema.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class NotificationsApplicationTests {

    @Test
    fun contextLoads() {
    }
}
