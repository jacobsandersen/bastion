package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.microsub.events.EntryEvents
import dev.jacobandersen.bastion.microsub.feeds.FeedDetails
import dev.jacobandersen.bastion.microsub.feeds.FeedRefreshCommandService
import dev.jacobandersen.bastion.microsub.feeds.FeedStateQueryService
import dev.jacobandersen.bastion.reader.internal.ReaderApi
import dev.jacobandersen.bastion.reader.internal.security.ReaderAuthentication
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Bastion's reader API: the capability document, on-demand refresh, the live entries stream and feed details.
 * Everything except the capability document needs a Bearer token with the Microsub `read` scope.
 */
@RestController
@RequestMapping(ReaderApi.PATH)
class ReaderController(
    @Value($$"${bastion.public-url}") publicUrl: String,
    private val feedState: FeedStateQueryService,
    private val refreshCommands: FeedRefreshCommandService,
    private val events: EntryEvents,
) {
    private val capabilities =
        ReaderCapabilities(
            api = ReaderApi.NAME,
            version = ReaderApi.VERSION,
            microsubEndpoint = publicUrl.trim().trimEnd('/') + ReaderApi.MICROSUB_PATH,
            features = ReaderApi.FEATURES,
        )

    /** Public. A client reads it after finding `bastion_reader_api` in the IndieAuth metadata. */
    @GetMapping
    fun capabilities(): ResponseEntity<ReaderCapabilities> =
        ResponseEntity.ok().cacheControl(CacheControl.maxAge(CAPABILITIES_MAX_AGE).cachePublic()).body(capabilities)

    /** Starts a background fetch of the channel's stale feeds and answers at once with how many it started. */
    @PostMapping("/channels/{uid}/refresh")
    fun refresh(
        @PathVariable uid: String,
    ): Map<String, Int> {
        requireScope()
        return mapOf("refreshing" to (refreshCommands.refreshStale(uid) ?: throw ReaderError.channelNotFound(uid)))
    }

    /** Server-sent `entries` events (`{"channel":uid,"count":n}`) as feed fetches store new entries. */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun eventStream(): ResponseEntity<SseEmitter> {
        requireScope()
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")
            .body(events.subscribe())
    }

    /** The channel's followed feeds with the details Microsub has no field for. */
    @GetMapping("/channels/{uid}/feeds")
    fun feeds(
        @PathVariable uid: String,
    ): Map<String, List<Map<String, String>>> {
        requireScope()
        val feeds = feedState.list(uid) ?: throw ReaderError.channelNotFound(uid)
        return mapOf("items" to feeds.map(::view))
    }

    private fun view(feed: FeedDetails): Map<String, String> =
        buildMap {
            put("url", feed.url)
            feed.name?.let { put("name", it) }
            feed.photo?.let { put("photo", it) }
            feed.description?.let { put("description", it) }
            put("format", feed.format)
            feed.websub?.let { put("websub", it) }
        }

    private fun requireScope() {
        val auth = SecurityContextHolder.getContext().authentication as? ReaderAuthentication ?: throw ReaderError.unauthorized()
        if (ReaderApi.REQUIRED_SCOPE !in auth.scopes) throw ReaderError.insufficientScope(ReaderApi.REQUIRED_SCOPE)
    }

    // An explicit JSON content type keeps these answers valid for the event stream route, whose Accept is text/event-stream.
    @ExceptionHandler(ReaderError::class)
    fun handle(error: ReaderError): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON).body(error.body())

    /** Anything else keeps the `error`/`error_description` shape: framework 4xx errors map to `invalid_request`. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<Map<String, String>> {
        if (error is ErrorResponse && error.statusCode.is4xxClientError) {
            val body = ReaderError(HttpStatus.valueOf(error.statusCode.value()), "invalid_request", error.body.detail ?: "Invalid request")
            return ResponseEntity
                .status(body.status)
                .headers(error.headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.body())
        }
        logger.error(error) { "Unexpected error handling a reader API request" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("error" to "server_error", "error_description" to "The request could not be processed"))
    }

    private companion object {
        val CAPABILITIES_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
