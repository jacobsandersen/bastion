package dev.jacobandersen.bastion.webmention.internal.data.repository

import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.entity.ReceivedWebmentionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ReceivedWebmentionRepository : JpaRepository<ReceivedWebmentionEntity, UUID> {
    fun findBySourceUrlAndPostId(
        sourceUrl: String,
        postId: UUID,
    ): ReceivedWebmentionEntity?

    fun findByPostId(postId: UUID): List<ReceivedWebmentionEntity>

    fun findByViaId(viaId: UUID): List<ReceivedWebmentionEntity>

    fun findByPostIdAndState(
        postId: UUID,
        state: ReceivedWebmentionState,
    ): List<ReceivedWebmentionEntity>

    fun findByPostIdInAndState(
        postIds: Collection<UUID>,
        state: ReceivedWebmentionState,
    ): List<ReceivedWebmentionEntity>
}
