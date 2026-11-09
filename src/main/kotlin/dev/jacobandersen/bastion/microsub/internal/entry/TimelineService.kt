package dev.jacobandersen.bastion.microsub.internal.entry

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Raw paging and filter parameters as the client sent them. */
data class PageRequest(
    val before: String?,
    val after: String?,
    val limit: String?,
)

/**
 * Timelines and content search. Pages are always newest first, ordered by when an entry was first stored and then by publish date. `before` is
 * returned whenever items are, and `after` whenever more matching items were
 * not returned, as the spec's paging rules require. That includes a
 * `before`-only poll that finds more new entries than fit in one page: it
 * returns the newest page, and the client closes the gap with the original
 * `before` plus the returned `after`.
 */
@Service
class TimelineService(
    private val channels: ChannelService,
    private val entries: EntryRepository,
    private val deletion: EntryDeletionService,
    private val config: MicrosubConfig,
) {
    @Transactional(readOnly = true)
    fun timeline(
        channelUid: String?,
        page: PageRequest,
        sources: List<String>,
    ): Map<String, Any> {
        val channel = channels.require(channelUid)
        return render(
            page,
            EntryQuery(listOf(channel.id), cursor(page.before), cursor(page.after), limit(page.limit), sources = normalizeSources(sources)),
        )
    }

    @Transactional(readOnly = true)
    fun search(
        channelUid: String?,
        page: PageRequest,
        queries: List<String>,
        authors: List<String>,
        categories: List<String>,
        kinds: List<String>,
        sources: List<String>,
    ): Map<String, Any> {
        val channelId = channels.requireOrGlobal(channelUid)
        val requestedAuthors = authors.map(String::trim).filter(String::isNotEmpty)
        val query =
            EntryQuery(
                channelIds = channelId?.let(::listOf),
                before = cursor(page.before),
                after = cursor(page.after),
                limit = limit(page.limit),
                sources = normalizeSources(sources),
                queries = queries.map(String::trim).filter(String::isNotEmpty),
                authors = requestedAuthors.mapNotNull(MicrosubUrls::fromUserInput),
                categories = categories.map(String::trim).filter(String::isNotEmpty),
                kinds = kinds.map { it.trim().lowercase() }.filter(String::isNotEmpty),
            )
        if (query.queries.isEmpty() && requestedAuthors.isEmpty() && query.categories.isEmpty() && query.kinds.isEmpty() &&
            query.sources.isEmpty()
        ) {
            throw MicrosubError.invalidRequest("Search needs at least one of query, author, category, kind or source")
        }
        // Filters are ANDed: an author filter with no usable profile URL can match nothing, not everything.
        if (requestedAuthors.isNotEmpty() &&
            query.authors.isEmpty()
        ) {
            return mapOf("items" to emptyList<Any>(), "paging" to emptyMap<String, Any>())
        }
        return render(page, query)
    }

    @Transactional
    fun setRead(
        channelUid: String?,
        entryIds: List<String>,
        read: Boolean,
    ) {
        val channel = channels.require(channelUid)
        entries.setRead(channel.id, ids(entryIds), read)
    }

    @Transactional
    fun markReadThrough(
        channelUid: String?,
        entryId: String,
    ) {
        val channel = channels.require(channelUid)
        val id = parseId(entryId) ?: throw MicrosubError.invalidRequest("Unknown entry '$entryId'")
        entries.markReadThrough(channel.id, id)
    }

    @Transactional
    fun remove(
        channelUid: String?,
        entryIds: List<String>,
    ) {
        val channel = channels.require(channelUid)
        deletion.tombstone(channel.id, ids(entryIds))
    }

    private fun render(
        page: PageRequest,
        query: EntryQuery,
    ): Map<String, Any> {
        val rows = entries.page(query)
        val items = rows.take(query.limit)
        val paging =
            buildMap {
                items.firstOrNull()?.let { put("before", it.cursor.encode()) }
                if (rows.size > query.limit) put("after", items.last().cursor.encode())
            }
        return mapOf("items" to items.map { it.toJf2() }, "paging" to paging)
    }

    private fun cursor(value: String?): Cursor? {
        if (value.isNullOrBlank()) return null
        Cursor.decode(value)?.let { return it }
        // A cursor from before timelines were ordered by arrival: resolve the entry's position now, or start over
        // from the newest page if it no longer exists (clients de-duplicate by entry id).
        Cursor.legacyEntryId(value)?.let { return entries.cursorOf(it) }
        throw MicrosubError.invalidRequest("Invalid paging cursor")
    }

    private fun limit(value: String?): Int {
        if (value.isNullOrBlank()) return config.defaultLimit
        val parsed = value.trim().toIntOrNull() ?: throw MicrosubError.invalidRequest("'limit' must be a number")
        return parsed.coerceIn(1, config.maxLimit)
    }

    private fun normalizeSources(sources: List<String>): List<String> =
        sources
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { listOfNotNull(it, MicrosubUrls.normalize(it)) }
            .distinct()

    private fun ids(values: List<String>): List<UUID> =
        values.map { parseId(it) ?: throw MicrosubError.invalidRequest("Unknown entry '$it'") }

    private fun parseId(value: String): UUID? = runCatching { UUID.fromString(value.trim()) }.getOrNull()
}
