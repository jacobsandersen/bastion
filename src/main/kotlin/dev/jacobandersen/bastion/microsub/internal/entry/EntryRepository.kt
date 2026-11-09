package dev.jacobandersen.bastion.microsub.internal.entry

import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class EntrySource(
    val id: UUID,
    val url: String,
    val name: String?,
    val photo: String?,
)

data class EntryRow(
    val id: UUID,
    val channelId: UUID,
    val createdAt: Instant,
    val published: Instant,
    val isRead: Boolean,
    val data: Map<String, Any?>,
    val source: EntrySource?,
) {
    val cursor: Cursor get() = Cursor(createdAt, published, id)
}

/**
 * Filters for a timeline or search page. [channelIds] null means every
 * channel (`global`). Within one filter, values are ORed; filters are ANDed.
 */
data class EntryQuery(
    val channelIds: List<UUID>?,
    val before: Cursor?,
    val after: Cursor?,
    val limit: Int,
    val sources: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
)

@Repository
class EntryRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Inserts or refreshes an entry. Read state and removal survive a
     * refresh, so re-fetching a feed never resurrects removed entries or
     * marks read ones unread. An undated entry keeps the time it was first
     * seen ([dated] false) instead of moving to the top on every fetch.
     * Every upsert records when the entry was last seen. Returns whether the entry is new.
     */
    fun upsert(
        channelId: UUID,
        feedId: UUID?,
        sourceKey: String,
        item: Jf2Item,
        published: Instant,
        dated: Boolean,
        read: Boolean,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                insert into microsub_entries (id, channel_id, feed_id, source_key, published, author_url, url, categories, kinds,
                                              data, search, is_read, created_at, updated_at, last_seen_at)
                values (:id, :channel, :feed, :key, :published, :author, :url, $TEXT_ARRAY_CATEGORIES, $TEXT_ARRAY_KINDS,
                        cast(:data as jsonb), to_tsvector('simple', :search), :read, :now, :now, :now)
                on conflict (channel_id, source_key) do update set
                    feed_id = coalesce(excluded.feed_id, microsub_entries.feed_id),
                    published = case when :dated and $DATA_CHANGED then excluded.published else microsub_entries.published end,
                    author_url = excluded.author_url,
                    url = excluded.url,
                    categories = excluded.categories,
                    kinds = excluded.kinds,
                    data = excluded.data,
                    search = excluded.search,
                    updated_at = case when $DATA_CHANGED then excluded.updated_at else microsub_entries.updated_at end,
                    last_seen_at = excluded.last_seen_at
                returning (xmax = 0) as inserted
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("channel", channelId)
            .param("feed", feedId)
            .param("key", sourceKey)
            .param("published", Timestamp.from(published))
            .param("dated", dated)
            .param("author", item.authorUrl)
            .param("url", item.url)
            .param("categories", objectMapper.writeValueAsString(item.categories))
            .param("kinds", objectMapper.writeValueAsString(item.kinds.sorted()))
            .param("data", objectMapper.writeValueAsString(item.data))
            .param("search", item.searchText)
            .param("read", read)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> rs.getBoolean("inserted") }
            .single()

    /** Where an entry sits in the timeline now; null once the entry no longer exists. */
    fun cursorOf(id: UUID): Cursor? =
        jdbc
            .sql("select created_at, published from microsub_entries where id = :id")
            .param("id", id)
            .query { rs, _ -> Cursor(rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("published").toInstant(), id) }
            .optional()
            .orElse(null)

    /** Returns up to `limit + 1` rows, newest first, between the `before` and `after` cursors. */
    fun page(query: EntryQuery): List<EntryRow> {
        val where = mutableListOf("e.removed_at is null", NOT_MODERATED)
        val params = mutableMapOf<String, Any?>("limit" to query.limit + 1)

        query.channelIds?.let {
            where += "e.channel_id in (:channels)"
            params["channels"] = it.ifEmpty { listOf(UUID(0, 0)) }
        }
        query.after?.let {
            where += "(e.created_at, e.published, e.id) < (:afterCreated, :afterPublished, :afterId)"
            params["afterCreated"] = Timestamp.from(it.createdAt)
            params["afterPublished"] = Timestamp.from(it.published)
            params["afterId"] = it.id
        }
        query.before?.let {
            where += "(e.created_at, e.published, e.id) > (:beforeCreated, :beforePublished, :beforeId)"
            params["beforeCreated"] = Timestamp.from(it.createdAt)
            params["beforePublished"] = Timestamp.from(it.published)
            params["beforeId"] = it.id
        }
        if (query.sources.isNotEmpty()) {
            where += "(f.url in (:sources) or f.fetch_url in (:sources) or cast(f.id as text) in (:sources))"
            params["sources"] = query.sources
        }
        if (query.queries.isNotEmpty()) {
            where +=
                query.queries.indices.joinToString(" or ", "(", ")") { i ->
                    params["q$i"] = query.queries[i]
                    "e.search @@ websearch_to_tsquery('simple', :q$i)"
                }
        }
        if (query.authors.isNotEmpty()) {
            where += "e.author_url in (:authors)"
            params["authors"] = query.authors
        }
        if (query.categories.isNotEmpty()) {
            where += "e.categories && array[:categories]::text[]"
            params["categories"] = query.categories
        }
        if (query.kinds.isNotEmpty()) {
            where += "e.kinds && array[:kinds]::text[]"
            params["kinds"] = query.kinds
        }

        return jdbc
            .sql(
                """
                select e.id, e.channel_id, e.created_at, e.published, e.is_read, e.data::text as data,
                       f.id as feed_id, f.url as feed_url, f.name as feed_name, f.photo as feed_photo
                from microsub_entries e
                left join microsub_feeds f on f.id = e.feed_id
                where ${where.joinToString(" and ")}
                order by e.created_at desc, e.published desc, e.id desc
                limit :limit
                """.trimIndent(),
            ).params(params)
            .query(::map)
            .list()
    }

    fun setRead(
        channelId: UUID,
        ids: Collection<UUID>,
        read: Boolean,
    ): Int {
        if (ids.isEmpty()) return 0
        return jdbc
            .sql("update microsub_entries set is_read = :read where channel_id = :channel and id in (:ids)")
            .param("channel", channelId)
            .param("ids", ids)
            .param("read", read)
            .update()
    }

    /** Marks [entryId] and everything older in the channel read. */
    fun markReadThrough(
        channelId: UUID,
        entryId: UUID,
    ): Int =
        jdbc
            .sql(
                """
                update microsub_entries e set is_read = true
                from microsub_entries anchor
                where anchor.id = :entry and anchor.channel_id = :channel
                  and e.channel_id = :channel and not e.is_read
                  and (e.published, e.id) <= (anchor.published, anchor.id)
                """.trimIndent(),
            ).param("channel", channelId)
            .param("entry", entryId)
            .update()

    fun remove(
        channelId: UUID,
        ids: Collection<UUID>,
        now: Instant,
    ): Int {
        if (ids.isEmpty()) return 0
        return jdbc
            .sql("update microsub_entries set removed_at = :now where channel_id = :channel and id in (:ids) and removed_at is null")
            .param("channel", channelId)
            .param("ids", ids)
            .param("now", Timestamp.from(now))
            .update()
    }

    /** The subset of [ids] that are entries of the channel and not removed. */
    fun liveIds(
        channelId: UUID,
        ids: Collection<UUID>,
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return jdbc
            .sql("select id from microsub_entries where channel_id = :channel and id in (:ids) and removed_at is null")
            .param("channel", channelId)
            .param("ids", ids)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()
            .toSet()
    }

    fun existsBySourceKey(
        channelId: UUID,
        sourceKey: String,
    ): Boolean =
        jdbc
            .sql("select exists (select 1 from microsub_entries where channel_id = :channel and source_key = :key)")
            .param("channel", channelId)
            .param("key", sourceKey)
            .query(Boolean::class.java)
            .single()

    fun deleteBySourceKey(
        channelId: UUID,
        sourceKey: String,
    ): Int =
        jdbc
            .sql("delete from microsub_entries where channel_id = :channel and source_key = :key")
            .param("channel", channelId)
            .param("key", sourceKey)
            .update()

    /** Deletes an author's entries in one channel, or in every channel when [channelId] is null. */
    fun deleteByAuthor(
        channelId: UUID?,
        authorUrl: String,
    ): Int =
        jdbc
            .sql("delete from microsub_entries where author_url = :author and (cast(:channel as uuid) is null or channel_id = :channel)")
            .param("channel", channelId)
            .param("author", authorUrl)
            .update()

    @Suppress("UNCHECKED_CAST")
    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): EntryRow {
        val feedId = rs.getObject("feed_id", UUID::class.java)
        return EntryRow(
            id = rs.getObject("id", UUID::class.java),
            channelId = rs.getObject("channel_id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            published = rs.getTimestamp("published").toInstant(),
            isRead = rs.getBoolean("is_read"),
            data = objectMapper.readValue(rs.getString("data"), Map::class.java) as Map<String, Any?>,
            source =
                feedId?.let {
                    EntrySource(it, rs.getString("feed_url"), rs.getString("feed_name"), rs.getString("feed_photo"))
                },
        )
    }

    companion object {
        private const val TEXT_ARRAY_CATEGORIES = "array(select jsonb_array_elements_text(cast(:categories as jsonb)))"
        private const val TEXT_ARRAY_KINDS = "array(select jsonb_array_elements_text(cast(:kinds as jsonb)))"
        private const val DATA_CHANGED = "microsub_entries.data is distinct from excluded.data"

        private const val NOT_MODERATED =
            """not exists (
                select 1 from microsub_moderation m
                where m.url = e.author_url and (m.channel_id = e.channel_id or m.channel_id is null)
            )"""
    }
}
