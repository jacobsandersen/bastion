package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.microsub.entries.BulkDeleteRefusal
import dev.jacobandersen.bastion.microsub.entries.BulkDeleteScope
import dev.jacobandersen.bastion.microsub.entries.DeletedEntryQueryService
import dev.jacobandersen.bastion.microsub.entries.EntryBulkDeleteService
import dev.jacobandersen.bastion.microsub.entries.EntryStatusQueryService
import dev.jacobandersen.bastion.microsub.events.EntryEvents
import dev.jacobandersen.bastion.microsub.feeds.FeedDetails
import dev.jacobandersen.bastion.microsub.feeds.FeedRefreshCommandService
import dev.jacobandersen.bastion.microsub.feeds.FeedStateQueryService
import dev.jacobandersen.bastion.microsub.stats.StorageQueryService
import dev.jacobandersen.bastion.microsub.stats.UnreadQueryService
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * Bastion's reader API: the capability document, on-demand refresh, the live entries stream, feed details, the entry check, deleted
 * entries and bulk delete. Everything except the capability document needs a Bearer token with the Microsub `read` scope;
 * bulk delete also needs `channels`.
 */
@RestController
@RequestMapping(ReaderApi.PATH)
class ReaderController(
    @Value($$"${bastion.public-url}") publicUrl: String,
    private val feedState: FeedStateQueryService,
    private val refreshCommands: FeedRefreshCommandService,
    private val events: EntryEvents,
    private val entryStatus: EntryStatusQueryService,
    private val deleted: DeletedEntryQueryService,
    private val bulkDelete: EntryBulkDeleteService,
    private val unreadTotals: UnreadQueryService,
    private val storageStats: StorageQueryService,
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

    /**
     * Which of the entry ids a client has cached are gone from the channel (removed, hard-deleted or never there), so
     * it can drop them. Authoritative whatever removed the entry. JSON `{"entry": [...]}`.
     */
    @PostMapping("/channels/{uid}/entries/check", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun checkEntries(
        @PathVariable uid: String,
        @RequestBody body: EntryCheckRequest,
    ): Map<String, List<String>> = check(uid, body.entry)

    /** The same check with form-encoded `entry[]` (or `entry`) values, like the Microsub endpoint takes. */
    @PostMapping("/channels/{uid}/entries/check", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun checkEntriesForm(
        @PathVariable uid: String,
        @RequestParam("entry[]", required = false) bracketed: List<String>?,
        @RequestParam("entry", required = false) plain: List<String>?,
    ): Map<String, List<String>> = check(uid, (bracketed.orEmpty() + plain.orEmpty()))

    private fun check(
        uid: String,
        ids: List<String>?,
    ): Map<String, List<String>> {
        requireScope()
        if (ids.isNullOrEmpty()) throw ReaderError.invalidRequest("'entry' must list at least one entry id")
        if (ids.size > MAX_CHECK_IDS) throw ReaderError.invalidRequest("At most $MAX_CHECK_IDS entry ids can be checked at once")
        return mapOf("gone" to (entryStatus.gone(uid, ids) ?: throw ReaderError.channelNotFound(uid)))
    }

    /**
     * The entries the owner deleted from the channel, most recently deleted first, each with a `_deleted_at` time. A
     * deleted entry is kept, with its content, only while its feed still lists it (so it is not added again); once
     * the feed no longer lists it, it is permanently deleted. The `note` says so for the client to show.
     */
    @GetMapping("/channels/{uid}/deleted")
    fun deletedEntries(
        @PathVariable uid: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<Map<String, Any>> {
        requireScope()
        val page = deleted.list(uid, after, limit) ?: throw ReaderError.channelNotFound(uid)
        val body =
            mapOf("note" to DELETED_NOTE, "items" to page.items, "paging" to listOfNotNull(page.after?.let { "after" to it }).toMap())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    /**
     * Deletes many entries at once. A dry run (the default) answers what the scope matches: count, unread count,
     * stored bytes and the published range. Executing (`dry_run: false`) must quote that count as `expected` and
     * deletes at most one chunk of 5,000 entries, answering how many are `remaining`; the client repeats the call until
     * none are. It is refused with 409 `scope_changed`, deleting nothing, when more entries match now than expected,
     * so it can never delete more than the owner was shown. Unread entries need `state: "all"` and `confirm_unread`.
     * Needs the `read` and `channels` scopes.
     */
    @PostMapping("/entries/delete", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteEntries(
        @RequestBody body: BulkDeleteRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val auth = requireScope()
        requireScope(ReaderApi.BULK_DELETE_SCOPE)
        val scope = bulkDeleteScope(body.scope)
        val result =
            try {
                if (body.dryRun ?: true) {
                    val preview = bulkDelete.preview(scope)
                    mapOf(
                        "matched" to preview.matched,
                        "unread" to preview.unread,
                        "permanent" to preview.permanent,
                        "bytes" to preview.bytes,
                        "oldest" to preview.oldest?.toString(),
                        "newest" to preview.newest?.toString(),
                        "by_channel" to preview.byChannel.map { mapOf("uid" to it.uid, "matched" to it.matched) },
                    )
                } else {
                    val expected = body.expected ?: throw ReaderError.invalidRequest("'expected' must quote the dry run's matched count")
                    if (expected < 0) throw ReaderError.invalidRequest("'expected' must not be negative")
                    if (scope.includeUnread && body.confirmUnread != true) {
                        throw ReaderError.invalidRequest("Deleting unread entries needs 'confirm_unread': true")
                    }
                    val chunk = bulkDelete.deleteChunk(scope, expected, auth.clientId)
                    mapOf("deleted" to chunk.deleted, "remaining" to chunk.remaining)
                }
            } catch (refusal: BulkDeleteRefusal) {
                throw when (refusal) {
                    is BulkDeleteRefusal.ChannelNotFound -> ReaderError.channelNotFound(refusal.uid)
                    is BulkDeleteRefusal.ScopeChanged -> ReaderError.scopeChanged(refusal.matched, refusal.expected)
                    is BulkDeleteRefusal.Busy -> ReaderError.busy()
                }
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    /** Validates the request's scope: some narrowing filter is required, so no call can mean everything everywhere. */
    private fun bulkDeleteScope(request: BulkDeleteScopeRequest?): BulkDeleteScope {
        if (request == null) throw ReaderError.invalidRequest("'scope' is required")
        val includeUnread =
            when (request.state) {
                null, "read" -> false
                "all" -> true
                else -> throw ReaderError.invalidRequest("'state' must be 'read' or 'all'")
            }
        val before =
            request.publishedBefore?.let {
                try {
                    Instant.parse(it)
                } catch (_: DateTimeParseException) {
                    throw ReaderError.invalidRequest("'published_before' must be an ISO-8601 instant such as 2026-08-01T00:00:00Z")
                }
            }
        val channel = request.channel?.trim()?.takeIf { it.isNotEmpty() }
        val feed = request.feed?.trim()?.takeIf { it.isNotEmpty() }
        val unfollowed = request.unfollowed == true
        val everywhere = channel == null || channel == EntryBulkDeleteService.GLOBAL
        if (everywhere && feed == null && before == null && !unfollowed) {
            throw ReaderError.invalidRequest("Name a channel or feed, or give 'published_before' or 'unfollowed'")
        }
        return BulkDeleteScope(channel, feed, includeUnread, before, unfollowed)
    }

    /**
     * Unread entries on the server: the `total` (without the `notifications` channel), each channel and each followed
     * feed. Entries marked deleted are not unread. Always current.
     */
    @GetMapping("/unread")
    fun unread(): ResponseEntity<Map<String, Any>> {
        requireScope()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(unreadTotals.totals().toBody())
    }

    /**
     * What Microsub stores: counts and bytes per channel and feed, overall, plus the database size and any configured
     * warning that is exceeded. Computed at most once per cache period on each replica (`computed_at`, `ttl_seconds`).
     */
    @GetMapping("/storage")
    fun storage(): ResponseEntity<Map<String, Any?>> {
        requireScope()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(storageStats.report().toBody())
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

    private fun requireScope(scope: String = ReaderApi.REQUIRED_SCOPE): ReaderAuthentication {
        val auth = SecurityContextHolder.getContext().authentication as? ReaderAuthentication ?: throw ReaderError.unauthorized()
        if (scope !in auth.scopes) throw ReaderError.insufficientScope(scope)
        return auth
    }

    // An explicit JSON content type keeps these answers valid for the event stream route, whose Accept is text/event-stream.
    @ExceptionHandler(ReaderError::class)
    fun handle(error: ReaderError): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON).body(error.body())

    /** Anything else keeps the `error`/`error_description` shape: framework 4xx errors map to `invalid_request`. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<Map<String, Any>> {
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
        const val MAX_CHECK_IDS = 500

        const val DELETED_NOTE =
            "Deleted entries are kept only while their feed still lists them, so that they are not added again. " +
                "Each is permanently deleted once its feed no longer lists it."
    }
}
