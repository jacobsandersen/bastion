package dev.jacobandersen.bastion.websub.internal.subscriber

import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The WebSub subscriber callback. Unauthenticated by design: hubs prove
 * themselves by echoing the topic during verification and by signing content
 * with the per-subscription secret.
 */
@RestController
@RequestMapping("${WebsubSubscriber.CALLBACK_PATH}/{id}")
class WebsubCallbackController(
    private val subscriber: WebsubSubscriber,
    private val config: WebsubConfig,
) {
    @GetMapping
    fun verify(
        @PathVariable id: String,
        @RequestParam("hub.mode", required = false) mode: String?,
        @RequestParam("hub.topic", required = false) topic: String?,
        @RequestParam("hub.challenge", required = false) challenge: String?,
        @RequestParam("hub.lease_seconds", required = false) leaseSeconds: String?,
        @RequestParam("hub.reason", required = false) reason: String?,
    ): ResponseEntity<String> {
        val subscriptionId = parseId(id) ?: return ResponseEntity.notFound().build()
        val body =
            subscriber.verifyIntent(subscriptionId, mode, topic, challenge, leaseSeconds?.toLongOrNull(), reason)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body)
    }

    @PostMapping
    fun deliver(
        @PathVariable id: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val subscriptionId = parseId(id) ?: return ResponseEntity.status(HttpStatus.GONE).build()
        val body = readBounded(request)
        return when (subscriber.receiveContent(subscriptionId, request.getHeader(SIGNATURE_HEADER), body)) {
            DeliveryOutcome.GONE -> ResponseEntity.status(HttpStatus.GONE).build()
            DeliveryOutcome.ACCEPTED, DeliveryOutcome.IGNORED -> ResponseEntity.accepted().build()
        }
    }

    /** The raw body, or null when it exceeds the configured limit. */
    private fun readBounded(request: HttpServletRequest): ByteArray? {
        val limit = config.subscriber.maxContentBytes
        val bytes = request.inputStream.readNBytes(limit + 1)
        return bytes.takeIf { it.size <= limit }
    }

    private fun parseId(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    companion object {
        const val SIGNATURE_HEADER = "X-Hub-Signature"
    }
}
