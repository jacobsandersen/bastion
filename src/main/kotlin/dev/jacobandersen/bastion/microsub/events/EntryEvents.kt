package dev.jacobandersen.bastion.microsub.events

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Tells connected readers when a feed fetch stored new entries, as server-sent events. In-process only: an event
 * reaches the clients connected to the replica that ran the fetch, so with several replicas a client may see only
 * some of them and should keep polling as a fallback. Published to peer modules (`microsub::microsub-events`) so the
 * `reader` module can serve the stream; the legacy `/microsub/events` route uses the same hub.
 */
@Component
class EntryEvents {
    private val emitters = CopyOnWriteArraySet<SseEmitter>()
    private val sender = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "microsub-events").apply { isDaemon = true } }

    init {
        sender.scheduleWithFixedDelay({ broadcast(SseEmitter.event().comment("keepalive")) }, KEEPALIVE, KEEPALIVE, TimeUnit.SECONDS)
    }

    /** Registers a client, sending a first comment so the response starts and proxies open the stream. */
    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(TIMEOUT.toMillis())
        emitters += emitter
        emitter.onCompletion { emitters -= emitter }
        emitter.onTimeout { emitter.complete() }
        emitter.onError { emitters -= emitter }
        sender.execute { send(emitter, SseEmitter.event().reconnectTime(RECONNECT.toMillis()).comment("connected")) }
        return emitter
    }

    fun publish(
        channelUid: String,
        count: Int,
    ) {
        sender.execute {
            broadcast(SseEmitter.event().name("entries").data("""{"channel":"${channelUid.escaped()}","count":$count}"""))
        }
    }

    @PreDestroy
    fun close() {
        sender.shutdownNow()
        emitters.forEach { runCatching { it.complete() } }
    }

    private fun broadcast(event: SseEmitter.SseEventBuilder) = emitters.forEach { send(it, event) }

    private fun send(
        emitter: SseEmitter,
        event: SseEmitter.SseEventBuilder,
    ) {
        try {
            emitter.send(event)
        } catch (e: Exception) {
            logger.debug { "Dropping a closed event stream: ${e.message}" }
            emitters -= emitter
        }
    }

    private fun String.escaped() = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val KEEPALIVE = 25L
        private val TIMEOUT: Duration = Duration.ofHours(1)
        private val RECONNECT: Duration = Duration.ofSeconds(5)
    }
}
