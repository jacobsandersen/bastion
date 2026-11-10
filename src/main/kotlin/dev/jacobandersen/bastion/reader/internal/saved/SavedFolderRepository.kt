package dev.jacobandersen.bastion.reader.internal.saved

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class SavedFolder(
    val id: UUID,
    val uid: String,
    val name: String,
    val position: Int,
    val version: Long,
)

/** A folder with what it holds. [bytes] is the size of its item rows (whole rows, `pg_column_size`). */
data class SavedFolderSummary(
    val folder: SavedFolder,
    val count: Long,
    val bytes: Long,
)

@Repository
class SavedFolderRepository(
    private val jdbc: JdbcClient,
) {
    fun list(): List<SavedFolder> = jdbc.sql("select * from reader_saved_folders order by position, created_at, id").query(::map).list()

    fun summaries(): List<SavedFolderSummary> =
        jdbc
            .sql(
                """
                select f.*, count(i.id) as item_count, coalesce(sum(pg_column_size(i.*)), 0) as item_bytes
                from reader_saved_folders f
                left join reader_saved_items i on i.folder_id = f.id
                group by f.id
                order by f.position, f.created_at, f.id
                """.trimIndent(),
            ).query { rs, row -> SavedFolderSummary(map(rs, row), rs.getLong("item_count"), rs.getLong("item_bytes")) }
            .list()

    fun summary(id: UUID): SavedFolderSummary? = summaries().firstOrNull { it.folder.id == id }

    fun findByUid(uid: String): SavedFolder? =
        jdbc
            .sql("select * from reader_saved_folders where uid = :uid")
            .param("uid", uid)
            .query(::map)
            .optional()
            .orElse(null)

    /** Like [findByUid] but takes a row lock until the transaction ends, serializing folder deletes and item moves into it. */
    fun lockByUid(uid: String): SavedFolder? =
        jdbc
            .sql("select * from reader_saved_folders where uid = :uid for update")
            .param("uid", uid)
            .query(::map)
            .optional()
            .orElse(null)

    fun uidExists(uid: String): Boolean =
        jdbc
            .sql("select exists (select 1 from reader_saved_folders where uid = :uid)")
            .param("uid", uid)
            .query(Boolean::class.java)
            .single()

    fun nextPosition(): Int =
        jdbc
            .sql("select coalesce(max(position), -1) + 1 from reader_saved_folders")
            .query(Int::class.java)
            .single()

    fun insert(
        uid: String,
        name: String,
        position: Int,
        now: Instant,
    ): SavedFolder {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into reader_saved_folders (id, uid, name, position, version, created_at, updated_at)
                values (:id, :uid, :name, :position, 0, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("uid", uid)
            .param("name", name)
            .param("position", position)
            .param("now", Timestamp.from(now))
            .update()
        return SavedFolder(id, uid, name, position, 0)
    }

    fun rename(
        id: UUID,
        name: String,
        now: Instant,
    ) {
        jdbc
            .sql("update reader_saved_folders set name = :name, updated_at = :now where id = :id")
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
            .sql("update reader_saved_folders set position = :position where id = :id")
            .param("id", id)
            .param("position", position)
            .update()
    }

    /** Records that the folder's items changed, so a client syncing by [SavedFolder.version] re-reads it. */
    fun bumpVersion(
        ids: Collection<UUID>,
        now: Instant,
    ) {
        if (ids.isEmpty()) return
        jdbc
            .sql("update reader_saved_folders set version = version + 1, updated_at = :now where id in (:ids)")
            .param("ids", ids.distinct())
            .param("now", Timestamp.from(now))
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from reader_saved_folders where id = :id").param("id", id).update()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = SavedFolder(
        id = rs.getObject("id", UUID::class.java),
        uid = rs.getString("uid"),
        name = rs.getString("name"),
        position = rs.getInt("position"),
        version = rs.getLong("version"),
    )
}
