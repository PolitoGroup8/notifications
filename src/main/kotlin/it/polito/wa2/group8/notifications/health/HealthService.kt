package it.polito.wa2.group8.notifications.health

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs all the dependency checks, in parallel, each with a time limit.
 *
 * Why a time limit: with the database down, getting a connection waits up to the pool
 * timeout (30 s by default), and opening a RabbitMQ connection up to 60 s. Without a limit
 * the health request would hang instead of answering 503, and the Docker healthcheck that
 * calls it would time out without a clear answer.
 *
 * A check that throws, returns false, or does not answer within [checkTimeout] counts as down.
 * Checks run on virtual threads: a check stuck on I/O costs almost nothing while it waits.
 */
@Service
class HealthService(
    private val checks: List<DependencyCheck>,
    @Value("\${notifications.health.check-timeout:2s}") private val checkTimeout: Duration,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(HealthService::class.java)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /** Names of the checks that are down; empty if everything is up. */
    fun failedChecks(): List<String> {
        val results = checks.associate { check ->
            check.name to CompletableFuture
                .supplyAsync({ check.isUp() }, executor)
                .completeOnTimeout(false, checkTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally { error ->
                    logger.debug("Health check '{}' failed: {}", check.name, error.message)
                    false
                }
        }
        val failed = results.filterValues { !it.join() }.keys.toList()
        if (failed.isNotEmpty()) logger.warn("Health: not ready, failed checks: {}", failed)
        return failed
    }

    /** Called by Spring at shutdown: stops the threads of checks still running. */
    override fun close() {
        executor.shutdownNow()
    }
}
