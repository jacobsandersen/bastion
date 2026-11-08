package dev.jacobandersen.bastion.microsub.internal.web

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.entry.PageRequest
import dev.jacobandersen.bastion.microsub.internal.entry.TimelineService
import dev.jacobandersen.bastion.microsub.internal.feed.DiscoveryService
import dev.jacobandersen.bastion.microsub.internal.feed.FollowService
import dev.jacobandersen.bastion.microsub.internal.live.EntryEvents
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationKind
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationService
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubAuthentication
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.BLOCK
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.CHANNELS
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.FOLLOW
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.MUTE
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.READ
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.MultiValueMap
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

private val logger = KotlinLogging.logger {}

/**
 * The single Microsub endpoint. Requests are RPC-style: `action` (and for
 * some POSTs `method`) picks the operation; parameters come from the query
 * string or a form body. Each action checks the scope it needs.
 */
@RestController
@RequestMapping("/microsub")
class MicrosubController(
    private val channels: ChannelService,
    private val timeline: TimelineService,
    private val follows: FollowService,
    private val discovery: DiscoveryService,
    private val moderation: ModerationService,
    private val events: EntryEvents,
) {
    @GetMapping
    fun get(
        @RequestParam params: MultiValueMap<String, String>,
    ): ResponseEntity<Any> {
        val p = Params(params)
        val body: Any =
            when (val action = p.action()) {
                "channels" -> {
                    require(READ)
                    mapOf("channels" to channels.list())
                }

                "timeline" -> {
                    require(READ)
                    // The spec's own paging example omits `channel` ("the default channel").
                    val channel = if (p.has("channel")) p.one("channel") else channels.defaultUid()
                    timeline.timeline(channel, p.page(), p.all("source"))
                }

                "follow" -> {
                    require(READ)
                    mapOf("items" to follows.list(p.one("channel")))
                }

                "mute" -> {
                    require(READ)
                    moderation.list(p.one("channel"), ModerationKind.MUTE)
                }

                "block" -> {
                    require(READ)
                    moderation.list(p.one("channel"), ModerationKind.BLOCK)
                }

                "search" -> {
                    require(READ)
                    search(p)
                }

                "preview" -> {
                    require(READ)
                    discovery.preview(p.one("url"))
                }

                else -> {
                    throw MicrosubError.invalidRequest("Unsupported action '$action'")
                }
            }
        return ResponseEntity.ok(body)
    }

    /** Server-sent `entries` events (`{"channel":uid,"count":n}`) as feed fetches store new entries. */
    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun eventStream(): ResponseEntity<SseEmitter> {
        require(READ)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering", "no")
            .body(events.subscribe())
    }

    /**
     * Not part of the Microsub spec, so it is a sibling of the endpoint and the action namespace stays conformant:
     * starts a background fetch of the channel's stale feeds and answers at once with how many it started.
     */
    @PostMapping("/refresh")
    fun refresh(
        @RequestParam params: MultiValueMap<String, String>,
    ): ResponseEntity<Any> {
        require(READ)
        return ResponseEntity.ok(mapOf("refreshing" to follows.refreshStale(Params(params).one("channel"))))
    }

    @PostMapping
    fun post(
        @RequestParam params: MultiValueMap<String, String>,
    ): ResponseEntity<Any> {
        val p = Params(params)
        val body: Any =
            when (val action = p.action()) {
                "channels" -> {
                    channelsPost(p)
                }

                "timeline" -> {
                    timelinePost(p)
                }

                "follow" -> {
                    require(FOLLOW)
                    follows.follow(p.one("channel"), p.one("url"))
                }

                "unfollow" -> {
                    require(FOLLOW)
                    follows.unfollow(p.one("channel"), p.one("url"))
                    EMPTY
                }

                "mute", "unmute" -> {
                    require(MUTE)
                    moderate(p, ModerationKind.MUTE, add = action == "mute")
                }

                "block", "unblock" -> {
                    require(BLOCK)
                    moderate(p, ModerationKind.BLOCK, add = action == "block")
                }

                "search" -> {
                    require(READ)
                    search(p)
                }

                "preview" -> {
                    require(READ)
                    discovery.preview(p.one("url"))
                }

                else -> {
                    throw MicrosubError.invalidRequest("Unsupported action '$action'")
                }
            }
        return ResponseEntity.ok(body)
    }

    private fun channelsPost(p: Params): Any {
        require(CHANNELS)
        return when (p.one("method")) {
            "delete" -> {
                channels.delete(p.one("channel"))
                EMPTY
            }

            "order" -> {
                channels.order(p.all("channels"))
                EMPTY
            }

            null -> {
                // A present-but-blank `channel` is an update of an unknown channel, not a create.
                val channel = if (p.has("channel")) channels.rename(p.one("channel"), p.one("name")) else channels.create(p.one("name"))
                mapOf("uid" to channel.uid, "name" to channel.name)
            }

            else -> {
                throw MicrosubError.invalidRequest("Unsupported channels method '${p.one("method")}'")
            }
        }
    }

    private fun timelinePost(p: Params): Any {
        require(READ)
        val channel = p.one("channel")
        when (val method = p.one("method")) {
            "mark_read" -> {
                val through = p.one("last_read_entry")
                if (through != null) timeline.markReadThrough(channel, through) else timeline.setRead(channel, p.entries(), read = true)
            }

            "mark_unread" -> {
                timeline.setRead(channel, p.entries(), read = false)
            }

            "remove" -> {
                timeline.remove(channel, p.entries())
            }

            else -> {
                throw MicrosubError.invalidRequest("Unsupported timeline method '$method'")
            }
        }
        return EMPTY
    }

    private fun search(p: Params): Any =
        // The presence of `channel` (even blank) makes this a content search.
        if (!p.has("channel")) {
            discovery.searchFeeds(p.one("query"))
        } else {
            timeline.search(
                channelUid = p.one("channel"),
                page = p.page(),
                queries = p.all("query"),
                authors = p.all("author"),
                categories = p.all("category"),
                kinds = p.all("kind"),
                sources = p.all("source"),
            )
        }

    private fun moderate(
        p: Params,
        kind: ModerationKind,
        add: Boolean,
    ): Any {
        if (add) moderation.add(p.one("channel"), p.one("url"), kind) else moderation.remove(p.one("channel"), p.one("url"), kind)
        return EMPTY
    }

    private fun require(scope: MicrosubScope) {
        val auth = SecurityContextHolder.getContext().authentication as? MicrosubAuthentication ?: throw MicrosubError.unauthorized()
        if (scope !in auth.scopes) throw MicrosubError.insufficientScope(scope.wireName)
    }

    /** Methods the endpoint does not serve get the Micropub error shape instead of the framework's default body. */
    @RequestMapping(method = [RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE])
    fun unsupportedMethod(): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .header(HttpHeaders.ALLOW, "GET, HEAD, POST, OPTIONS")
            .body(MicrosubError.invalidRequest("Only GET and POST are supported").body())

    @ExceptionHandler(MicrosubError::class)
    fun handle(error: MicrosubError): ResponseEntity<Map<String, String>> = ResponseEntity.status(error.status).body(error.body())

    /** Anything else keeps the `error`/`error_description` shape: framework 4xx errors map to `invalid_request`. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<Map<String, String>> {
        if (error is ErrorResponse && error.statusCode.is4xxClientError) {
            val body =
                MicrosubError(HttpStatus.valueOf(error.statusCode.value()), "invalid_request", error.body.detail ?: "Invalid request")
            return ResponseEntity.status(body.status).headers(error.headers).body(body.body())
        }
        logger.error(error) { "Unexpected error handling a Microsub request" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "server_error", "error_description" to "The request could not be processed"))
    }

    /** Request parameters, reading both `name` and the PHP-style `name[]` for list values. */
    private class Params(
        private val params: MultiValueMap<String, String>,
    ) {
        /** Whether the parameter was sent at all, even blank. */
        fun has(name: String): Boolean = params.containsKey(name) || params.containsKey("$name[]")

        fun one(name: String): String? = params.getFirst(name)?.trim()?.takeIf { it.isNotEmpty() }

        fun all(name: String): List<String> =
            (params[name].orEmpty() + params["$name[]"].orEmpty()).map(String::trim).filter(String::isNotEmpty)

        fun action(): String = one("action") ?: throw MicrosubError.invalidRequest("The 'action' parameter is required")

        fun entries(): List<String> = all("entry").ifEmpty { throw MicrosubError.invalidRequest("The 'entry' parameter is required") }

        fun page() = PageRequest(one("before"), one("after"), one("limit"))
    }

    companion object {
        private val EMPTY = emptyMap<String, Any>()
    }
}
