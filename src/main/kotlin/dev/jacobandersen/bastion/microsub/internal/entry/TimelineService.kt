package dev.jacobandersen.bastion.microsub.internal.entry

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Raw paging and filter parameters as the client sent them. */
data class PageRequest(
    val before: String?,
    val after: String?,
    val limit: String?,
)

/**
 * Timelines and content search (spec sections 5 and 7). Pages are newest
 * first. `before` is returned whenever items are, and `after` only when
 * older items remain, as the paging rules require. A `before`-only page holds
 * the entries just newer than the cursor, so nothing older than it remains
 * unseen and it carries no `after`.
 */
@Service
class TimelineService(
    private val channels: ChannelService,
    private val entries: EntryRepository,
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
        val query =
            EntryQuery(
                channelIds = channelId?.let(::listOf),
                before = cursor(page.before),
                after = cursor(page.after),
                limit = limit(page.limit),
                sources = normalizeSources(sources),
                queries = queries.map(String::trim).filter(String::isNotEmpty),
                authors = authors.mapNotNull(MicrosubUrls::fromUserInput),
                categories = categories.map(String::trim).filter(String::isNotEmpty),
                kinds = kinds.map { it.trim().lowercase() }.filter(String::isNotEmpty),
            )
        if (query.queries.isEmpty() && query.authors.isEmpty() && query.categories.isEmpty() && query.kinds.isEmpty() &&
            query.sources.isEmpty()
        ) {
            throw MicrosubError.invalidRequest("Search needs at least one of query, author, category, kind or source")
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
        entries.remove(channel.id, ids(entryIds), Instant.now())
    }

    private fun render(
        page: PageRequest,
        query: EntryQuery,
    ): Map<String, Any> {
        val rows = entries.page(query)
        val page = rows.take(query.limit)
        val items = if (query.ascending) page.reversed() else page
        val paging =
            buildMap {
                items.firstOrNull()?.let { put("before", it.cursor.encode()) }
                if (!query.ascending && rows.size > query.limit) put("after", items.last().cursor.encode())
            }
        return mapOf("items" to items.map(::serve), "paging" to paging)
    }

    private fun serve(row: EntryRow): Map<String, Any?> =
        LinkedHashMap(row.data).apply {
            put("_id", row.id.toString())
            put("_is_read", row.isRead)
            row.source?.let { source ->
                put(
                    "_source",
                    buildMap {
                        put("_id", source.id.toString())
                        put("url", source.url)
                        source.name?.let { put("name", it) }
                        source.photo?.let { put("photo", it) }
                    },
                )
            }
        }

    private fun cursor(value: String?): Cursor? {
        if (value.isNullOrBlank()) return null
        return Cursor.decode(value) ?: throw MicrosubError.invalidRequest("Invalid paging cursor")
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

    private fun ids(values: List<String>): List<UUID> = values.mapNotNull(::parseId)

    private fun parseId(value: String): UUID? = runCatching { UUID.fromString(value.trim()) }.getOrNull()
}
