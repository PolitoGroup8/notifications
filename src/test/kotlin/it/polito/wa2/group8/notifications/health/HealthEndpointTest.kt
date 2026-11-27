package it.polito.wa2.group8.notifications.health

import it.polito.wa2.group8.notifications.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/** Sends a real HTTP request to the running application (random port). */
private fun getHealth(environment: Environment, accept: String = "*/*"): HttpResponse<String> {
    val port = environment.getRequiredProperty("local.server.port")
    val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/health"))
        .header("Accept", accept)
        .GET()
        .build()
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
}

/** With the real PostgreSQL and RabbitMQ up, the service is ready. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class HealthEndpointTest {

    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var jsonMapper: JsonMapper

    @Test
    fun `answers 200 UP when database and broker are ready`() {
        val response = getHealth(environment)

        assertEquals(200, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null))
        // exactly {"status":"UP"}, no other field (additionalProperties: false in notifications.yaml)
        assertEquals(jsonMapper.readTree("""{"status":"UP"}"""), jsonMapper.readTree(response.body()))
    }
}

/** One dependency down (a fake check that always fails, added to the real ones). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, HealthEndpointDownTest.FailingCheckConfig::class)
class HealthEndpointDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    class FailingCheckConfig {
        @Bean
        fun failingCheck() = object : DependencyCheck {
            override val name = "always-down"
            override fun isUp() = false
        }
    }

    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var jsonMapper: JsonMapper

    @Test
    fun `answers 503 with the not-ready problem document`() {
        // Accept: application/json, as a typical client would send: the problem is still returned.
        val response = getHealth(environment, accept = "application/json")

        assertEquals(503, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(null))

        val problem = jsonMapper.readTree(response.body())
        assertEquals("https://tickets.example.com/problems/not-ready", problem["type"].asString())
        assertEquals("Notifications not ready", problem["title"].asString())
        assertEquals(503, problem["status"].asInt())
        assertEquals("not-ready", problem["code"].asString())
        assertEquals("The database or broker consumer is not ready.", problem["detail"].asString())
    }
}
