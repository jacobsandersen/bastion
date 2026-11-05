package dev.jacobandersen.bastion.microsub.internal.web

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.entry.PageRequest
import dev.jacobandersen.bastion.microsub.internal.entry.TimelineService
import dev.jacobandersen.bastion.microsub.internal.feed.DiscoveryService
import dev.jacobandersen.bastion.microsub.internal.feed.FollowService
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationKind
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationService
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubAuthentication
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.BLOCK
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.CHANNELS
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.FOLLOW
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.MUTE
import dev.jacobandersen.bastion.microsub.internal.security.MicrosubScope.READ
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
                    timeline.timeline(p.one("channel"), p.page(), p.all("source"))
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
                val channel = p.one("channel")?.let { channels.rename(it, p.one("name")) } ?: channels.create(p.one("name"))
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
        if (p.one("channel") == null) {
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

    @ExceptionHandler(MicrosubError::class)
    fun handle(error: MicrosubError): ResponseEntity<Map<String, String>> = ResponseEntity.status(error.status).body(error.body())

    /** Request parameters, reading both `name` and the PHP-style `name[]` for list values. */
    private class Params(
        private val params: MultiValueMap<String, String>,
    ) {
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
