package dev.jacobandersen.bastion.reader.internal.saved

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A saved item as stored. [data] is the jf2 copy taken at save time. The `origin*` members are provenance only. */
data class SavedItem(
    val id: UUID,
    val folderId: UUID,
    val folderUid: String,
    val dedupeKey: String,
    val originChannelUid: String?,
    val originEntryId: UUID?,
    val originFeedUrl: String?,
    val url: String?,
    val data: Map<String, Any?>,
    val savedAt: Instant,
)

/** What to store for a new item. */
data class NewSavedItem(
    val folderId: UUID,
    val dedupeKey: String,
    val originChannelUid: String,
    val originEntryId: UUID?,
    val originFeedUrl: String?,
    val url: String?,
    val authorUrl: String?,
    val published: Instant,
    val categories: List<String>,
    val kinds: Collection<String>,
    val data: Map<String, Any?>,
    val searchText: String,
)

@Repository
class SavedItemRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {
    /** Stores the item unless the folder already holds its [NewSavedItem.dedupeKey]; returns the new id, or null then. */
    fun insertIfAbsent(
        item: NewSavedItem,
        now: Instant,
    ): UUID? =
        jdbc
            .sql(
                """
                insert into reader_saved_items (id, folder_id, dedupe_key, origin_channel_uid, origin_entry_id, origin_feed_url, url,
                                                author_url, published, categories, kinds, data, search, saved_at, updated_at)
                values (:id, :folder, :key, :channel, :entry, :feed, :url, :author, :published,
                        array(select jsonb_array_elements_text(cast(:categories as jsonb))),
                        array(select jsonb_array_elements_text(cast(:kinds as jsonb))),
                        cast(:data as jsonb), to_tsvector('simple', :search), :now, :now)
                on conflict (folder_id, dedupe_key) do nothing
                returning id
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("folder", item.folderId)
            .param("key", item.dedupeKey)
            .param("channel", item.originChannelUid)
            .param("entry", item.originEntryId)
            .param("feed", item.originFeedUrl)
            .param("url", item.url)
            .param("author", item.authorUrl)
            .param("published", Timestamp.from(item.published))
            .param("categories", objectMapper.writeValueAsString(item.categories))
            .param("kinds", objectMapper.writeValueAsString(item.kinds.sorted()))
            .param("data", objectMapper.writeValueAsString(item.data))
            .param("search", item.searchText)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .optional()
            .orElse(null)

    fun find(id: UUID): SavedItem? =
        select("i.id = :id")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun findInFolder(
        folderId: UUID,
        dedupeKey: String,
    ): SavedItem? =
        select("i.folder_id = :folder and i.dedupe_key = :key")
            .param("folder", folderId)
            .param("key", dedupeKey)
            .query(::map)
            .optional()
            .orElse(null)

    fun findAll(ids: Collection<UUID>): List<SavedItem> =
        if (ids.isEmpty()) emptyList() else select("i.id in (:ids)").param("ids", ids).query(::map).list()

    /**
     * Up to [limit] + 1 items, newest saved first, after [after]. [folderId] null means every folder; [query] is a
     * web-style full-text search (`websearch_to_tsquery`) over the item's text, name, author and categories.
     */
    fun page(
        folderId: UUID?,
        query: String?,
        after: SavedCursor?,
        limit: Int,
    ): List<SavedItem> =
        jdbc
            .sql(
                """
                select i.*, i.data::text as data_text, f.uid as folder_uid
                from reader_saved_items i
                join reader_saved_folders f on f.id = i.folder_id
                where (cast(:folder as uuid) is null or i.folder_id = :folder)
                  and (cast(:q as text) is null or i.search @@ websearch_to_tsquery('simple', :q))
                  and (cast(:afterAt as timestamptz) is null
                       or (i.saved_at, i.id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid)))
                order by i.saved_at desc, i.id desc
                limit :limit
                """.trimIndent(),
            ).param("folder", folderId)
            .param("q", query)
            .param("afterAt", after?.let { Timestamp.from(it.savedAt) })
            .param("afterId", after?.id)
            .param("limit", limit + 1)
            .query(::map)
            .list()

    fun countInFolder(folderId: UUID): Long =
        jdbc
            .sql("select count(*) from reader_saved_items where folder_id = :folder")
            .param("folder", folderId)
            .query(Long::class.java)
            .single()

    fun moveTo(
        id: UUID,
        folderId: UUID,
        now: Instant,
    ) {
        jdbc
            .sql("update reader_saved_items set folder_id = :folder, updated_at = :now where id = :id")
            .param("id", id)
            .param("folder", folderId)
            .param("now", Timestamp.from(now))
            .update()
    }

    /**
     * Moves every item of [from] into [to], dropping the ones [to] already holds (same dedupe key). Returns how many
     * were moved.
     */
    fun moveAll(
        from: UUID,
        to: UUID,
        now: Instant,
    ): Int {
        jdbc
            .sql(
                """
                delete from reader_saved_items
                where folder_id = :from and dedupe_key in (select dedupe_key from reader_saved_items where folder_id = :to)
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .update()
        return jdbc
            .sql("update reader_saved_items set folder_id = :to, updated_at = :now where folder_id = :from")
            .param("from", from)
            .param("to", to)
            .param("now", Timestamp.from(now))
            .update()
    }

    /** Deletes the items and returns the folders they were in, one entry per deleted item. */
    fun delete(ids: Collection<UUID>): List<UUID> {
        if (ids.isEmpty()) return emptyList()
        return jdbc
            .sql("delete from reader_saved_items where id in (:ids) returning folder_id")
            .param("ids", ids)
            .query { rs, _ -> rs.getObject("folder_id", UUID::class.java) }
            .list()
    }

    private fun select(where: String): JdbcClient.StatementSpec =
        jdbc.sql(
            """
            select i.*, i.data::text as data_text, f.uid as folder_uid
            from reader_saved_items i
            join reader_saved_folders f on f.id = i.folder_id
            where $where
            """.trimIndent(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = SavedItem(
        id = rs.getObject("id", UUID::class.java),
        folderId = rs.getObject("folder_id", UUID::class.java),
        folderUid = rs.getString("folder_uid"),
        dedupeKey = rs.getString("dedupe_key"),
        originChannelUid = rs.getString("origin_channel_uid"),
        originEntryId = rs.getObject("origin_entry_id", UUID::class.java),
        originFeedUrl = rs.getString("origin_feed_url"),
        url = rs.getString("url"),
        data = objectMapper.readValue(rs.getString("data_text"), Map::class.java) as Map<String, Any?>,
        savedAt = rs.getTimestamp("saved_at").toInstant(),
    )
}
