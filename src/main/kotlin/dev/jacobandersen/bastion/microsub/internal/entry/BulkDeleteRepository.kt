package dev.jacobandersen.bastion.microsub.internal.entry

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Which live entries a bulk delete covers. All filters are ANDed. [channelId] null means every channel; [feed] is a
 * feed url, fetch url or id; [unfollowed] selects the entries an unfollowed feed left behind.
 */
data class BulkDeleteFilter(
    val channelId: UUID?,
    val feed: String?,
    val includeUnread: Boolean,
    val publishedBefore: Instant?,
    val unfollowed: Boolean,
)

/** What a filter matches in one channel. [permanent] is how many of the matches are deleted for good rather than marked. */
data class BulkDeleteChannelTotals(
    val channelId: UUID,
    val channelUid: String,
    val matched: Int,
    val unread: Int,
    val permanent: Int,
    val bytes: Long,
    val oldest: Instant,
    val newest: Instant,
)

/** An entry picked for deletion. [orphaned] entries have no feed any more, so nothing lists them. */
data class BulkDeleteCandidate(
    val id: UUID,
    val channelId: UUID,
    val orphaned: Boolean,
)

@Repository
class BulkDeleteRepository(
    private val jdbc: JdbcClient,
) {
    /** Takes the cluster-wide bulk delete lock for the current transaction; false when another transaction holds it. */
    fun tryLock(): Boolean =
        jdbc
            .sql("select pg_try_advisory_xact_lock(:key)")
            .param("key", LOCK_KEY)
            .query(Boolean::class.java)
            .single()

    /** What [filter] matches now, per channel. */
    fun totals(filter: BulkDeleteFilter): List<BulkDeleteChannelTotals> {
        val (where, params) = where(filter)
        return jdbc
            .sql(
                """
                select e.channel_id, c.uid,
                       count(*) as matched,
                       count(*) filter (where not e.is_read) as unread,
                       count(*) filter (where $ORPHANED) as permanent,
                       coalesce(sum(pg_column_size(e.*)), 0) as bytes,
                       min(e.published) as oldest, max(e.published) as newest
                from microsub_entries e
                join microsub_channels c on c.id = e.channel_id
                where $where
                group by e.channel_id, c.uid
                order by c.uid
                """.trimIndent(),
            ).params(params)
            .query { rs, _ ->
                BulkDeleteChannelTotals(
                    channelId = rs.getObject("channel_id", UUID::class.java),
                    channelUid = rs.getString("uid"),
                    matched = rs.getInt("matched"),
                    unread = rs.getInt("unread"),
                    permanent = rs.getInt("permanent"),
                    bytes = rs.getLong("bytes"),
                    oldest = rs.getTimestamp("oldest").toInstant(),
                    newest = rs.getTimestamp("newest").toInstant(),
                )
            }.list()
    }

    /**
     * Locks up to [limit] matching entries, skipping any a concurrent transaction (a feed fetch) holds. Call inside a
     * transaction; the locks last until it ends.
     */
    fun lockCandidates(
        filter: BulkDeleteFilter,
        limit: Int,
    ): List<BulkDeleteCandidate> {
        val (where, params) = where(filter)
        return jdbc
            .sql(
                """
                select e.id, e.channel_id, ($ORPHANED) as orphaned
                from microsub_entries e
                where $where
                order by e.id
                limit :limit
                for update of e skip locked
                """.trimIndent(),
            ).params(params)
            .param("limit", limit)
            .query { rs, _ ->
                BulkDeleteCandidate(
                    id = rs.getObject("id", UUID::class.java),
                    channelId = rs.getObject("channel_id", UUID::class.java),
                    orphaned = rs.getBoolean("orphaned"),
                )
            }.list()
    }

    /** Permanently deletes the entries. */
    fun delete(ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return jdbc
            .sql("delete from microsub_entries where id in (:ids)")
            .param("ids", ids)
            .update()
    }

    private fun where(filter: BulkDeleteFilter): Pair<String, Map<String, Any?>> {
        val where = mutableListOf("e.removed_at is null")
        val params = mutableMapOf<String, Any?>()
        filter.channelId?.let {
            where += "e.channel_id = :channel"
            params["channel"] = it
        }
        filter.feed?.let {
            where +=
                "e.feed_id in (select f.id from microsub_feeds f where f.url = :feed or f.fetch_url = :feed or cast(f.id as text) = :feed)"
            params["feed"] = it
        }
        if (!filter.includeUnread) where += "e.is_read"
        filter.publishedBefore?.let {
            where += "e.published < :before"
            params["before"] = Timestamp.from(it)
        }
        if (filter.unfollowed) where += ORPHANED
        return where.joinToString(" and ") to params
    }

    private companion object {
        /** Stable key of the advisory lock that serialises bulk deletes. */
        const val LOCK_KEY = 7_424_117_001L

        /** What an unfollowed feed leaves behind: feed entries that no feed row owns. Webmention entries never match. */
        const val ORPHANED = "e.feed_id is null and e.source_key like 'feed:%'"
    }
}
