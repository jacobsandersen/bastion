package dev.jacobandersen.bastion.post.internal.types

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class PostTypeChange(
    val id: UUID,
    val previousType: String?,
    val type: String?,
)

@Service
class PostTypeBackfillWriter(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun updateTypes(changes: List<PostTypeChange>) {
        if (changes.isEmpty()) return

        jdbcTemplate.batchUpdate(
            "update posts set type = ? where id = ? and type is not distinct from ?",
            changes.map { change -> arrayOf<Any?>(change.type, change.id, change.previousType) },
        )
    }
}
