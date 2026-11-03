package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.entity.PostSyndicationEntity
import dev.jacobandersen.bastion.post.internal.model.PostSyndication
import dev.jacobandersen.bastion.post.internal.repository.PostSyndicationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PostSyndicationService(
    private val repository: PostSyndicationRepository,
) {
    /**
     * Applies the requested target set for a post. Targets that are no longer
     * requested are marked undesired instead of being deleted immediately, so
     * the reconciliation listener can retract any held copy first.
     */
    @Transactional
    fun syncDesiredTargets(
        postId: UUID,
        targets: List<SyndicationConfig.Target>,
    ) {
        val existing = repository.findByPostId(postId).associateBy { it.targetUid }

        targets.forEachIndexed { index, target ->
            val entity = existing[target.uid]
            if (entity == null) {
                repository.save(
                    PostSyndicationEntity(
                        postId = postId,
                        targetUid = target.uid,
                        targetName = target.name,
                        sortOrder = index,
                    ),
                )
            } else {
                entity.targetName = target.name
                entity.sortOrder = index
                entity.desired = true
                repository.save(entity)
            }
        }

        val desiredUids = targets.map { it.uid }.toSet()
        existing.values
            .filter { it.targetUid !in desiredUids }
            .forEach { entity ->
                entity.desired = false
                repository.save(entity)
            }
    }

    /**
     * Claims a dispatch operation for a post/target pair. Returns the claim
     * token when the claim was acquired, or null when another operation is
     * still in flight. Stale claims can be reclaimed after the lease expires.
     */
    @Transactional
    fun claim(
        postId: UUID,
        targetUid: String,
        action: String,
        now: Instant,
        staleBefore: Instant,
    ): UUID? {
        val token = UUID.randomUUID()
        val claimed =
            repository.claim(
                postId = postId,
                targetUid = targetUid,
                action = action,
                now = now,
                staleBefore = staleBefore,
                token = token,
            ) == 1
        return token.takeIf { claimed }
    }

    @Transactional
    fun clearPending(
        postId: UUID,
        targetUid: String,
        token: UUID,
    ) {
        repository.clearPending(postId, targetUid, token)
    }

    @Transactional
    fun recordOutcome(
        postId: UUID,
        targetUid: String,
        claimToken: UUID,
        syndicatedUrl: String,
        sourceUrl: String,
        syncedUpdated: String?,
    ) {
        val entity = repository.findByPostIdAndTargetUid(postId, targetUid) ?: return
        if (entity.pendingToken != claimToken) return
        entity.syndicatedUrl = syndicatedUrl
        entity.sourceUrl = sourceUrl
        entity.lastSyncedUpdated = syncedUpdated
        entity.pendingAction = null
        entity.pendingSince = null
        entity.pendingToken = null
        repository.save(entity)
    }

    @Transactional
    fun recordSynced(
        postId: UUID,
        targetUid: String,
        claimToken: UUID,
        syncedUpdated: String?,
    ) {
        val entity = repository.findByPostIdAndTargetUid(postId, targetUid) ?: return
        if (entity.pendingToken != claimToken) return
        entity.lastSyncedUpdated = syncedUpdated
        entity.pendingAction = null
        entity.pendingSince = null
        entity.pendingToken = null
        repository.save(entity)
    }

    /**
     * Clears the held-copy state after a retraction has been dispatched. A
     * later publish/reconcile will create the copy again.
     */
    @Transactional
    fun markRetracted(
        postId: UUID,
        targetUid: String,
        claimToken: UUID,
    ) {
        val entity = repository.findByPostIdAndTargetUid(postId, targetUid) ?: return
        if (entity.pendingToken != claimToken) return
        entity.syndicatedUrl = null
        entity.sourceUrl = null
        entity.lastSyncedUpdated = null
        entity.pendingAction = null
        entity.pendingSince = null
        entity.pendingToken = null
        repository.save(entity)
    }

    @Transactional(readOnly = true)
    fun findByPostId(postId: UUID): List<PostSyndicationEntity> = repository.findByPostId(postId)

    @Transactional(readOnly = true)
    fun findByPostIdAndTargetUid(
        postId: UUID,
        targetUid: String,
    ): PostSyndicationEntity? = repository.findByPostIdAndTargetUid(postId, targetUid)

    @Transactional(readOnly = true)
    fun findPostIdsForReconciliation(): List<UUID> = repository.findDistinctPostIds()

    /**
     * Published syndicated copies of a post, for readers such as the public API.
     * Target display names and ordering are snapshotted when the target is
     * recorded, so readers do not depend on the writer's configuration.
     */
    @Transactional(readOnly = true)
    fun findLinksByPostId(postId: UUID): List<PostSyndication> = toLinks(repository.findByPostId(postId))

    /** Batched [findLinksByPostId] for feeds: one query, posts without links are absent from the map. */
    @Transactional(readOnly = true)
    fun findLinksByPostIds(postIds: Collection<UUID>): Map<UUID, List<PostSyndication>> {
        if (postIds.isEmpty()) return emptyMap()

        return repository
            .findByPostIdIn(postIds)
            .groupBy { it.postId }
            .mapValues { (_, entities) -> toLinks(entities) }
            .filterValues { it.isNotEmpty() }
    }

    private fun toLinks(entities: List<PostSyndicationEntity>): List<PostSyndication> =
        entities
            .mapNotNull { entity ->
                entity.syndicatedUrl?.let { url ->
                    PostSyndication(
                        targetUid = entity.targetUid,
                        targetName = entity.targetName,
                        syndicatedUrl = url,
                        sortOrder = entity.sortOrder,
                    )
                }
            }.sortedWith(compareBy({ it.sortOrder }, { it.targetName }))

    @Transactional
    fun remove(
        postId: UUID,
        targetUid: String,
    ) {
        repository.deleteByPostIdAndTargetUid(postId, targetUid)
    }
}
