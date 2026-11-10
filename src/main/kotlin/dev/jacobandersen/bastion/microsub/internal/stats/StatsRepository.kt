package dev.jacobandersen.bastion.microsub.internal.stats

import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationSql.NOT_MODERATED
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** Unread entries of one channel and followed feed (null: entries whose feed is no longer followed). */
data class UnreadRow(
    val channelId: UUID,
    val feedId: UUID?,
    val unread: Int,
)

/** What one channel and feed (null: entries without a followed feed) holds. [entries] excludes entries marked deleted. */
data class StorageRow(
    val channelId: UUID,
    val feedId: UUID?,
    val entries: Long,
    val unread: Long,
    val removed: Long,
    val bytes: Long,
)

/** A followed feed with its channel's uid. */
data class FollowedFeed(
    val id: UUID,
    val channelUid: String,
    val url: String,
)

@Repository
class StatsRepository(
    private val jdbc: JdbcClient,
) {
    /** Unread, live entries per channel and feed, not counting muted or blocked authors (the predicate `action=channels` uses). */
    fun unreadByFeed(): List<UnreadRow> =
        jdbc
            .sql(
                """
                select e.channel_id, e.feed_id, count(*) as unread
                from microsub_entries e
                where not e.is_read and e.removed_at is null
                  and $NOT_MODERATED
                group by e.channel_id, e.feed_id
                """.trimIndent(),
            ).query { rs, _ ->
                UnreadRow(rs.getObject("channel_id", UUID::class.java), rs.getObject("feed_id", UUID::class.java), rs.getInt("unread"))
            }.list()

    /**
     * Entries, unread, deleted markers and row payload per channel and feed in one pass. `bytes` is the whole row size
     * (`pg_column_size(e.*)`), which tracks the heap; summing the jsonb column alone would under-report.
     */
    fun storageByFeed(): List<StorageRow> =
        jdbc
            .sql(
                """
                select e.channel_id, e.feed_id,
                       count(*) filter (where e.removed_at is null) as entries,
                       count(*) filter (where not e.is_read and e.removed_at is null and $NOT_MODERATED) as unread,
                       count(*) filter (where e.removed_at is not null) as removed,
                       coalesce(sum(pg_column_size(e.*)), 0) as bytes
                from microsub_entries e
                group by e.channel_id, e.feed_id
                """.trimIndent(),
            ).query { rs, _ ->
                StorageRow(
                    channelId = rs.getObject("channel_id", UUID::class.java),
                    feedId = rs.getObject("feed_id", UUID::class.java),
                    entries = rs.getLong("entries"),
                    unread = rs.getLong("unread"),
                    removed = rs.getLong("removed"),
                    bytes = rs.getLong("bytes"),
                )
            }.list()

    /** Every followed feed, in channel order. */
    fun followedFeeds(): List<FollowedFeed> =
        jdbc
            .sql(
                """
                select f.id, c.uid, f.url
                from microsub_feeds f join microsub_channels c on c.id = f.channel_id
                order by c.position, c.created_at, f.url
                """.trimIndent(),
            ).query { rs, _ -> FollowedFeed(rs.getObject("id", UUID::class.java), rs.getString("uid"), rs.getString("url")) }
            .list()

    /** Heap, TOAST and indexes of the Microsub tables. */
    fun onDiskBytes(): Long =
        jdbc
            .sql(
                """
                select pg_total_relation_size('microsub_entries') + pg_total_relation_size('microsub_feeds')
                     + pg_total_relation_size('microsub_channels') + pg_total_relation_size('microsub_moderation')
                """.trimIndent(),
            ).query(Long::class.java)
            .single()

    /** Everything Bastion keeps in this database (posts, tokens, job and event tables too). */
    fun databaseBytes(): Long =
        jdbc
            .sql("select pg_database_size(current_database())")
            .query(Long::class.java)
            .single()
}
