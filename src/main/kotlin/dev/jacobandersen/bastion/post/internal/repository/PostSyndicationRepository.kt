package dev.jacobandersen.bastion.post.internal.repository

import dev.jacobandersen.bastion.post.internal.entity.PostSyndicationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface PostSyndicationRepository : JpaRepository<PostSyndicationEntity, UUID> {
    fun findByPostId(postId: UUID): List<PostSyndicationEntity>

    fun findByPostIdIn(postIds: Collection<UUID>): List<PostSyndicationEntity>

    fun findByPostIdAndTargetUid(
        postId: UUID,
        targetUid: String,
    ): PostSyndicationEntity?

    fun deleteByPostIdAndTargetUid(
        postId: UUID,
        targetUid: String,
    ): Long

    /**
     * Claims an operation for a post/target pair. Returns 1 when the claim was
     * acquired, 0 when another operation is still in flight.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update PostSyndicationEntity s
        set s.pendingAction = :action, s.pendingSince = :now, s.pendingToken = :token
        where s.postId = :postId and s.targetUid = :targetUid
          and (s.pendingAction is null or s.pendingSince is null or s.pendingSince < :staleBefore)
        """,
    )
    fun claim(
        @Param("postId") postId: UUID,
        @Param("targetUid") targetUid: String,
        @Param("action") action: String,
        @Param("now") now: Instant,
        @Param("staleBefore") staleBefore: Instant,
        @Param("token") token: UUID,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update PostSyndicationEntity s
        set s.pendingAction = null, s.pendingSince = null, s.pendingToken = null
        where s.postId = :postId and s.targetUid = :targetUid and s.pendingToken = :token
        """,
    )
    fun clearPending(
        @Param("postId") postId: UUID,
        @Param("targetUid") targetUid: String,
        @Param("token") token: UUID,
    ): Int

    @Query("select distinct s.postId from PostSyndicationEntity s")
    fun findDistinctPostIds(): List<UUID>
}
