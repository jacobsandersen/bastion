package dev.jacobandersen.bastion.post.internal.types

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Persists the desired/applied discovery ruleset fingerprint and guards the
 * reclassification job so only one replica runs it at a time.
 */
@Service
class PostTypeStateService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun recordDesired(fingerprint: String) {
        jdbcTemplate.update(
            """
            insert into post_type_state (id, desired_fingerprint)
            values (true, ?)
            on conflict (id) do update
                set desired_fingerprint = excluded.desired_fingerprint
            """.trimIndent(),
            fingerprint,
        )
    }

    /**
     * Attempts to claim the current desired fingerprint. Returns the claimed
     * fingerprint, or null when the ruleset is already applied or another run
     * still holds a non-stale claim.
     */
    fun tryClaim(
        now: Instant,
        lease: Duration,
    ): String? {
        val desired =
            jdbcTemplate
                .query("select desired_fingerprint from post_type_state where id = true") { rs, _ ->
                    rs.getString(1)
                }.firstOrNull()
                ?: return null

        val claimed =
            jdbcTemplate.update(
                """
                update post_type_state
                set running_since = ?, started_at = ?, processed_count = 0, changed_count = 0
                where id = true
                  and desired_fingerprint = ?
                  and desired_fingerprint is distinct from applied_fingerprint
                  and (running_since is null or running_since < ?)
                """.trimIndent(),
                Timestamp.from(now),
                Timestamp.from(now),
                desired,
                Timestamp.from(now.minus(lease)),
            )
        return if (claimed == 1) desired else null
    }

    fun complete(
        fingerprint: String,
        processedCount: Long,
        changedCount: Long,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            update post_type_state
            set applied_fingerprint = ?,
                running_since = null,
                completed_at = ?,
                processed_count = ?,
                changed_count = ?
            where id = true
            """.trimIndent(),
            fingerprint,
            Timestamp.from(now),
            processedCount,
            changedCount,
        )
    }

    fun release() {
        jdbcTemplate.update("update post_type_state set running_since = null where id = true")
    }
}
