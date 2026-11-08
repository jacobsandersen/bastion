package dev.jacobandersen.bastion.microsub.internal.channel

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class Channel(
    val id: UUID,
    val uid: String,
    val name: String,
    val position: Int,
)

@Repository
class ChannelRepository(
    private val jdbc: JdbcClient,
) {
    fun list(): List<Channel> = jdbc.sql("select * from microsub_channels order by position, created_at").query(::map).list()

    fun findByUid(uid: String): Channel? =
        jdbc
            .sql("select * from microsub_channels where uid = :uid")
            .param("uid", uid)
            .query(::map)
            .optional()
            .orElse(null)

    fun uidOf(id: UUID): String? =
        jdbc
            .sql("select uid from microsub_channels where id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun uidExists(uid: String): Boolean =
        jdbc
            .sql("select count(*) from microsub_channels where uid = :uid")
            .param("uid", uid)
            .query(Int::class.java)
            .single() > 0

    fun insert(
        uid: String,
        name: String,
        now: Instant,
    ): Channel {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into microsub_channels (id, uid, name, position, created_at, updated_at)
                values (:id, :uid, :name, (select coalesce(max(position), 0) + 1 from microsub_channels), :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("uid", uid)
            .param("name", name)
            .param("now", Timestamp.from(now))
            .update()
        return requireNotNull(findByUid(uid))
    }

    fun rename(
        id: UUID,
        name: String,
        now: Instant,
    ) {
        jdbc
            .sql("update microsub_channels set name = :name, updated_at = :now where id = :id")
            .param("id", id)
            .param("name", name)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun setPosition(
        id: UUID,
        position: Int,
    ) {
        jdbc
            .sql("update microsub_channels set position = :position where id = :id")
            .param("id", id)
            .param("position", position)
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from microsub_channels where id = :id").param("id", id).update()
    }

    /** Unread, non-removed entries per channel, not counting muted or blocked authors. */
    fun unreadCounts(): Map<UUID, Int> =
        jdbc
            .sql(
                """
                select e.channel_id, count(*) as unread
                from microsub_entries e
                where not e.is_read and e.removed_at is null
                  and not exists (
                    select 1 from microsub_moderation m
                    where m.url = e.author_url and (m.channel_id = e.channel_id or m.channel_id is null)
                  )
                group by e.channel_id
                """.trimIndent(),
            ).query { rs, _ -> rs.getObject("channel_id", UUID::class.java) to rs.getInt("unread") }
            .list()
            .toMap()

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Channel(
        id = rs.getObject("id", UUID::class.java),
        uid = rs.getString("uid"),
        name = rs.getString("name"),
        position = rs.getInt("position"),
    )
}
