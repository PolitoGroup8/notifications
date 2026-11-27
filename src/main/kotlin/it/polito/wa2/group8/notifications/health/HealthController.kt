package it.polito.wa2.group8.notifications.health

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** Body of the 200 response: exactly {"status": "UP"} (schema Health of notifications.yaml). */
data class HealthResponse(val status: String)

/**
 * GET /api/v1/health, as specified in notifications.yaml (M2): no authentication, used
 * inside the Compose network (e.g. by the container healthcheck).
 *
 *  - 200 {"status":"UP"}                      if the database and the broker consumer are ready;
 *  - 503 RFC 9457 problem, code "not-ready"    otherwise.
 * Both with Cache-Control: no-store, so that no proxy or client reuses an old answer.
 */
@RestController
@RequestMapping("/api/v1")
class HealthController(
    private val healthService: HealthService,
) {

    @GetMapping("/health")
    fun health(): ResponseEntity<Any> {
        if (healthService.failedChecks().isEmpty()) {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(HealthResponse("UP"))
        }

        // ProblemDetail is Spring's RFC 9457 document: type, title, status, detail, instance,
        // plus our extra "code" property, written as a top-level field.
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The database or broker consumer is not ready.",
        ).apply {
            type = URI.create("https://tickets.example.com/problems/not-ready")
            title = "Notifications not ready"
            setProperty("code", "not-ready")
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem)
    }
}
