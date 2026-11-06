package dev.jacobandersen.bastion.microsub.internal.moderation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class ModerationKind {
    MUTE,
    BLOCK,
}

/** Mutes and blocks of author profile URLs; a null channel means `global`. */
@Repository
class ModerationRepository(
    private val jdbc: JdbcClient,
) {
    fun add(
        channelId: UUID?,
        url: String,
        kind: ModerationKind,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into microsub_moderation (id, channel_id, url, kind, created_at)
                values (:id, :channel, :url, :kind, :now)
                on conflict do nothing
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("channel", channelId)
            .param("url", url)
            .param("kind", kind.name)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun remove(
        channelId: UUID?,
        url: String,
        kind: ModerationKind,
    ) {
        jdbc
            .sql("delete from microsub_moderation where channel_id is not distinct from :channel and url = :url and kind = :kind")
            .param("channel", channelId)
            .param("url", url)
            .param("kind", kind.name)
            .update()
    }

    /** Removes [url]'s [kind] rows from every channel and from `global`. */
    fun removeEverywhere(
        url: String,
        kind: ModerationKind,
    ) {
        jdbc
            .sql("delete from microsub_moderation where url = :url and kind = :kind")
            .param("url", url)
            .param("kind", kind.name)
            .update()
    }

    fun list(
        channelId: UUID?,
        kind: ModerationKind,
    ): List<String> =
        jdbc
            .sql("select url from microsub_moderation where channel_id is not distinct from :channel and kind = :kind order by created_at")
            .param("channel", channelId)
            .param("kind", kind.name)
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Every URL blocked in any channel or globally. */
    fun blockedUrls(): List<String> =
        jdbc
            .sql("select distinct url from microsub_moderation where kind = 'BLOCK'")
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Whether [authorUrl] is blocked in [channelId] or globally. */
    fun isBlocked(
        channelId: UUID,
        authorUrl: String,
    ): Boolean =
        jdbc
            .sql(
                """
                select count(*) from microsub_moderation
                where kind = 'BLOCK' and url = :url and (channel_id = :channel or channel_id is null)
                """.trimIndent(),
            ).param("channel", channelId)
            .param("url", authorUrl)
            .query(Int::class.java)
            .single() > 0
}
