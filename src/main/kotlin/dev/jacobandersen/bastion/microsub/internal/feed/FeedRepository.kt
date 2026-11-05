package dev.jacobandersen.bastion.microsub.internal.feed

import dev.jacobandersen.bastion.microsub.internal.fetch.FeedFormat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class Feed(
    val id: UUID,
    val channelId: UUID,
    val url: String,
    val fetchUrl: String,
    val format: FeedFormat,
    val name: String?,
    val photo: String?,
    val description: String?,
    val hub: String?,
    val selfUrl: String?,
    val subscriptionId: UUID?,
    val etag: String?,
    val lastModified: String?,
    val failureCount: Int,
)

/** What one fetch learned about a feed. */
data class FeedMetadata(
    val fetchUrl: String,
    val format: FeedFormat,
    val name: String?,
    val photo: String?,
    val description: String?,
    val hub: String?,
    val selfUrl: String?,
    val etag: String?,
    val lastModified: String?,
)

@Repository
class FeedRepository(
    private val jdbc: JdbcClient,
) {
    fun find(id: UUID): Feed? =
        jdbc
            .sql("select * from microsub_feeds where id = :id")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun findByChannelAndUrl(
        channelId: UUID,
        url: String,
    ): Feed? =
        jdbc
            .sql("select * from microsub_feeds where channel_id = :channel and url = :url")
            .param("channel", channelId)
            .param("url", url)
            .query(::map)
            .optional()
            .orElse(null)

    fun listByChannel(channelId: UUID): List<Feed> =
        jdbc
            .sql("select * from microsub_feeds where channel_id = :channel order by created_at")
            .param("channel", channelId)
            .query(::map)
            .list()

    fun listBySubscription(subscriptionId: UUID): List<Feed> =
        jdbc
            .sql("select * from microsub_feeds where websub_subscription_id = :sub")
            .param("sub", subscriptionId)
            .query(::map)
            .list()

    fun insert(
        channelId: UUID,
        url: String,
        metadata: FeedMetadata,
        nextFetchAt: Instant,
        now: Instant,
    ): Feed {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into microsub_feeds (id, channel_id, url, fetch_url, format, name, photo, description, hub, self_url,
                                            etag, last_modified, next_fetch_at, last_fetched_at, created_at)
                values (:id, :channel, :url, :fetchUrl, :format, :name, :photo, :description, :hub, :self,
                        :etag, :lastModified, :next, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("channel", channelId)
            .param("url", url)
            .params(metadataParams(metadata))
            .param("next", Timestamp.from(nextFetchAt))
            .param("now", Timestamp.from(now))
            .update()
        return requireNotNull(find(id))
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from microsub_feeds where id = :id").param("id", id).update()
    }

    fun setSubscription(
        id: UUID,
        subscriptionId: UUID?,
    ) {
        jdbc
            .sql("update microsub_feeds set websub_subscription_id = :sub where id = :id")
            .param("id", id)
            .param("sub", subscriptionId)
            .update()
    }

    fun countBySubscription(subscriptionId: UUID): Int =
        jdbc
            .sql("select count(*) from microsub_feeds where websub_subscription_id = :sub")
            .param("sub", subscriptionId)
            .query(Int::class.java)
            .single()

    /** Claims up to [limit] due, unclaimed feeds for one fetch each and returns their ids. */
    fun claimDue(
        now: Instant,
        claimUntil: Instant,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                update microsub_feeds set claimed_until = :until, fetch_started_at = :now
                where id in (
                    select id from microsub_feeds
                    where next_fetch_at <= :now and (claimed_until is null or claimed_until < :now)
                    order by next_fetch_at
                    limit :limit
                    for update skip locked
                )
                returning id
                """.trimIndent(),
            ).param("now", Timestamp.from(now))
            .param("until", Timestamp.from(claimUntil))
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    fun claim(
        id: UUID,
        now: Instant,
        claimUntil: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                update microsub_feeds set claimed_until = :until, fetch_started_at = :now
                where id = :id and (claimed_until is null or claimed_until < :now)
                """.trimIndent(),
            ).param("id", id)
            .param("now", Timestamp.from(now))
            .param("until", Timestamp.from(claimUntil))
            .update() == 1

    /** Asks for a fetch as soon as possible, even if one is running now (WebSub notification). */
    fun requestRefresh(
        id: UUID,
        now: Instant,
    ) {
        jdbc
            .sql("update microsub_feeds set refresh_requested_at = :now, next_fetch_at = least(next_fetch_at, :now) where id = :id")
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun recordSuccess(
        id: UUID,
        metadata: FeedMetadata?,
        nextFetchAt: Instant,
        now: Instant,
    ) {
        val sets =
            if (metadata == null) {
                ""
            } else {
                """
                fetch_url = :fetchUrl, format = :format, name = coalesce(:name, name), photo = coalesce(:photo, photo),
                description = coalesce(:description, description), hub = :hub, self_url = :self,
                etag = :etag, last_modified = :lastModified,
                """.trimIndent()
            }
        jdbc
            .sql(
                """
                update microsub_feeds set $sets
                    last_fetched_at = :now, last_error = null, failure_count = 0,
                    ${release()}
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .params(metadata?.let(::metadataParams).orEmpty())
            .param("next", Timestamp.from(nextFetchAt))
            .param("now", Timestamp.from(now))
            .update()
    }

    fun recordFailure(
        id: UUID,
        error: String,
        nextFetchAt: Instant,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update microsub_feeds set last_error = :error, failure_count = failure_count + 1,
                    ${release()}
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("error", error.take(MAX_ERROR))
            .param("next", Timestamp.from(nextFetchAt))
            .param("now", Timestamp.from(now))
            .update()
    }

    /** Releases the claim; a refresh requested while this fetch ran makes the feed due again right away. */
    private fun release() =
        """
        next_fetch_at = case when refresh_requested_at > fetch_started_at then :now else :next end,
        claimed_until = null
        """.trimIndent()

    private fun metadataParams(metadata: FeedMetadata): Map<String, Any?> =
        mapOf(
            "fetchUrl" to metadata.fetchUrl,
            "format" to metadata.format.name,
            "name" to metadata.name,
            "photo" to metadata.photo,
            "description" to metadata.description,
            "hub" to metadata.hub,
            "self" to metadata.selfUrl,
            "etag" to metadata.etag,
            "lastModified" to metadata.lastModified,
        )

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Feed(
        id = rs.getObject("id", UUID::class.java),
        channelId = rs.getObject("channel_id", UUID::class.java),
        url = rs.getString("url"),
        fetchUrl = rs.getString("fetch_url"),
        format = FeedFormat.valueOf(rs.getString("format")),
        name = rs.getString("name"),
        photo = rs.getString("photo"),
        description = rs.getString("description"),
        hub = rs.getString("hub"),
        selfUrl = rs.getString("self_url"),
        subscriptionId = rs.getObject("websub_subscription_id", UUID::class.java),
        etag = rs.getString("etag"),
        lastModified = rs.getString("last_modified"),
        failureCount = rs.getInt("failure_count"),
    )

    companion object {
        private const val MAX_ERROR = 1000
    }
}
