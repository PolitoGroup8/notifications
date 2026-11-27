package it.polito.wa2.group8.notifications.health

import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * One thing Notifications needs in order to work (database, broker...).
 * HealthService runs every bean implementing this interface.
 */
interface DependencyCheck {
    /** Short name used in the logs, e.g. "database". */
    val name: String

    /** True if the dependency is usable now. May throw or block: HealthService handles both. */
    fun isUp(): Boolean
}

/** PostgreSQL answers a trivial query. */
@Component
class DatabaseCheck(
    private val jdbc: JdbcTemplate,
) : DependencyCheck {
    override val name = "database"

    override fun isUp(): Boolean = jdbc.queryForObject("SELECT 1", Int::class.java) == 1
}

/**
 * The broker consumer is ready: the connection to RabbitMQ is open AND the listener
 * containers (the TicketsIssued consumer) are running.
 *
 * Both are needed: a listener container stays "running" while it keeps trying to reconnect
 * to a broker that is down, and an open connection is useless if the consumer has stopped.
 */
@Component
class BrokerCheck(
    private val connectionFactory: ConnectionFactory,
    private val listenerRegistry: RabbitListenerEndpointRegistry,
) : DependencyCheck {
    override val name = "broker"

    override fun isUp(): Boolean {
        // CachingConnectionFactory returns its shared connection: close() here does not close it.
        val connectionOpen = connectionFactory.createConnection().use { it.isOpen }
        val containers = listenerRegistry.listenerContainers
        return connectionOpen && containers.isNotEmpty() && containers.all { it.isRunning }
    }
}
