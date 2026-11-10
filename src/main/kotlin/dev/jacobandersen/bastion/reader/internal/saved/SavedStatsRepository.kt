package dev.jacobandersen.bastion.reader.internal.saved

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SavedStatsRepository(
    private val jdbc: JdbcClient,
) {
    /** Total size on disk of the two saved tables, with their indexes and TOAST data. */
    fun onDiskBytes(): Long =
        jdbc
            .sql(
                """
                select pg_total_relation_size('reader_saved_folders') + pg_total_relation_size('reader_saved_items')
                """.trimIndent(),
            ).query(Long::class.java)
            .single()
}
