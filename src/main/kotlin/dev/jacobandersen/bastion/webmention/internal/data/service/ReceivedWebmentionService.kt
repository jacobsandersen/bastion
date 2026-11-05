package dev.jacobandersen.bastion.webmention.internal.data.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.NestedResponse
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionAnalysis
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState.DELETED
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState.ERROR
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState.PENDING
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState.REJECTED
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState.VERIFIED
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionUpdate
import dev.jacobandersen.bastion.webmention.internal.data.entity.ReceivedWebmentionEntity
import dev.jacobandersen.bastion.webmention.internal.data.repository.ReceivedWebmentionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Persists received webmentions. Besides the webmentions sent to us directly,
 * a verified source can carry nested responses (Salmention); those are stored
 * as rows of their own with `viaId` pointing at the source that displays them.
 * A directly received webmention always takes precedence over a nested copy.
 */
@Service
class ReceivedWebmentionService(
    private val repository: ReceivedWebmentionRepository,
) {
    /**
     * Ensure a received webmention exists in [PENDING] state for the given
     * source and post, so verification can be (re)run against it. A webmention
     * that was previously deleted, rejected or errored is reopened, and a
     * nested copy of the same response becomes a direct webmention.
     */
    @Transactional
    fun ensurePending(
        sourceUrl: String,
        targetUrl: String,
        postId: UUID,
    ): ReceivedWebmention {
        val now = Instant.now()
        val existing = repository.findBySourceUrlAndPostId(sourceUrl, postId)
        if (existing == null) {
            val created =
                ReceivedWebmentionEntity(
                    postId = postId,
                    sourceUrl = sourceUrl,
                    targetUrl = targetUrl,
                    state = PENDING,
                    interaction = null,
                    authorName = null,
                    authorUrl = null,
                    authorPhoto = null,
                    contentText = null,
                    contentHtml = null,
                    rawMf2 = null,
                    lastError = null,
                    firstSeenAt = now,
                    verifiedAt = null,
                    updatedAtUtc = now,
                )
            return repository.save(created).toDomain()
        }

        if (existing.state != PENDING || existing.viaId != null) {
            existing.state = PENDING
            existing.viaId = null
            existing.targetUrl = targetUrl
            existing.lastError = null
            existing.verifiedAt = null
            existing.updatedAtUtc = now
            return repository.save(existing).toDomain()
        }
        return existing.toDomain()
    }

    /**
     * Records a verified source and replaces the set of nested responses it
     * displays. [ReceivedWebmentionUpdate.changed] reports whether anything
     * the target displays changed since the last verification.
     */
    @Transactional
    fun markVerified(
        sourceUrl: String,
        postId: UUID,
        analysis: ReceivedWebmentionAnalysis,
    ): ReceivedWebmentionUpdate {
        val now = Instant.now()
        val entity =
            repository.findBySourceUrlAndPostId(sourceUrl, postId)
                ?: throw IllegalStateException("No received webmention for $sourceUrl on post $postId")
        val previousFingerprint = entity.displayFingerprint

        entity.state = VERIFIED
        applyAnalysis(entity, analysis)
        entity.displayFingerprint = fingerprint(analysis)
        entity.lastError = null
        entity.verifiedAt = now
        entity.updatedAtUtc = now
        val saved = repository.save(entity)

        replaceNested(saved, analysis.nested, now)

        return ReceivedWebmentionUpdate(saved.toDomain(), changed = previousFingerprint != saved.displayFingerprint)
    }

    @Transactional
    fun markRejected(
        sourceUrl: String,
        postId: UUID,
        reason: String,
    ): ReceivedWebmentionUpdate = setTerminal(sourceUrl, postId, REJECTED, reason)

    @Transactional
    fun markDeleted(
        sourceUrl: String,
        postId: UUID,
    ): ReceivedWebmentionUpdate = setTerminal(sourceUrl, postId, DELETED, null)

    @Transactional
    fun markError(
        sourceUrl: String,
        postId: UUID,
        reason: String,
    ): ReceivedWebmentionUpdate = setTerminal(sourceUrl, postId, ERROR, reason)

    @Transactional(readOnly = true)
    fun notification(
        sourceUrl: String,
        postId: UUID,
    ): ReceivedWebmention? = repository.findBySourceUrlAndPostId(sourceUrl, postId)?.toDomain()

    @Transactional(readOnly = true)
    fun byPost(postId: UUID): List<ReceivedWebmention> = repository.findByPostId(postId).map { it.toDomain() }

    @Transactional(readOnly = true)
    fun verifiedByPost(postId: UUID): List<ReceivedWebmention> = repository.findByPostIdAndState(postId, VERIFIED).map { it.toDomain() }

    @Transactional(readOnly = true)
    fun verifiedByPostIds(postIds: Collection<UUID>): List<ReceivedWebmention> =
        repository.findByPostIdInAndState(postIds, VERIFIED).map { it.toDomain() }

    /**
     * Upserts the nested responses displayed by [via] and removes the ones it
     * no longer displays. Rows owned by a direct webmention or by another
     * source are left alone.
     */
    private fun replaceNested(
        via: ReceivedWebmentionEntity,
        nested: List<NestedResponse>,
        now: Instant,
    ) {
        val viaId = requireNotNull(via.id)
        val owned = mutableSetOf<UUID>()

        nested.forEach { response ->
            val existing = repository.findBySourceUrlAndPostId(response.url, via.postId)
            if (existing != null && existing.viaId != viaId) return@forEach

            val entity =
                existing ?: ReceivedWebmentionEntity(
                    postId = via.postId,
                    sourceUrl = response.url,
                    targetUrl = via.targetUrl,
                    state = VERIFIED,
                    interaction = null,
                    authorName = null,
                    authorUrl = null,
                    authorPhoto = null,
                    rawMf2 = null,
                    viaId = viaId,
                    lastError = null,
                    firstSeenAt = now,
                    verifiedAt = now,
                    updatedAtUtc = now,
                )
            val fingerprint = fingerprint(response.analysis)
            if (existing != null && existing.displayFingerprint == fingerprint) {
                owned += requireNotNull(existing.id)
                return@forEach
            }
            entity.state = VERIFIED
            entity.targetUrl = via.targetUrl
            applyAnalysis(entity, response.analysis)
            entity.displayFingerprint = fingerprint
            entity.verifiedAt = now
            entity.updatedAtUtc = now
            owned += requireNotNull(repository.save(entity).id)
        }

        val stale = repository.findByViaId(viaId).filterNot { requireNotNull(it.id) in owned }
        if (stale.isNotEmpty()) repository.deleteAll(stale)
    }

    private fun applyAnalysis(
        entity: ReceivedWebmentionEntity,
        analysis: ReceivedWebmentionAnalysis,
    ) {
        entity.interaction = analysis.interaction
        entity.authorName = analysis.authorName
        entity.authorUrl = analysis.authorUrl
        entity.authorPhoto = analysis.authorPhoto
        entity.contentText = analysis.contentText
        entity.contentHtml = analysis.contentHtml
        entity.rawMf2 = analysis.primary
        entity.url = analysis.url
        entity.inReplyTo = analysis.inReplyTo
    }

    private fun setTerminal(
        sourceUrl: String,
        postId: UUID,
        state: ReceivedWebmentionState,
        reason: String?,
    ): ReceivedWebmentionUpdate {
        val now = Instant.now()
        val entity =
            repository.findBySourceUrlAndPostId(sourceUrl, postId)
                ?: throw IllegalStateException("No received webmention for $sourceUrl on post $postId")
        val previousFingerprint = entity.displayFingerprint

        entity.state = state
        entity.lastError = reason
        entity.verifiedAt = null
        // An error is transient: keep what was displayed so recovering with the same content is not a change.
        if (state != ERROR) {
            entity.displayFingerprint = null
            entity.id?.let { id ->
                val nested = repository.findByViaId(id)
                if (nested.isNotEmpty()) repository.deleteAll(nested)
            }
        }
        if (state == DELETED) {
            entity.interaction = null
            entity.authorName = null
            entity.authorUrl = null
            entity.authorPhoto = null
            entity.contentText = null
            entity.contentHtml = null
            entity.rawMf2 = null
            entity.url = null
            entity.inReplyTo = null
        }
        entity.updatedAtUtc = now
        val saved = repository.save(entity)
        return ReceivedWebmentionUpdate(saved.toDomain(), changed = previousFingerprint != saved.displayFingerprint)
    }

    /**
     * A stable digest of everything the target displays for a response,
     * including the nested responses it carries, so a re-verification that
     * changes nothing visible is not reported as a change.
     */
    private fun fingerprint(analysis: ReceivedWebmentionAnalysis): String {
        val parts = mutableListOf<String>()

        fun add(response: ReceivedWebmentionAnalysis) {
            parts += response.interaction.name
            parts += response.authorName.orEmpty()
            parts += response.authorUrl.orEmpty()
            parts += response.authorPhoto.orEmpty()
            parts += response.url.orEmpty()
            parts += response.contentText.joinToString("\u001F")
            parts += response.contentHtml.joinToString("\u001F")
            parts += response.inReplyTo.joinToString("\u001F")
        }
        add(analysis)
        analysis.nested.sortedBy { it.url }.forEach { nested ->
            parts += nested.url
            add(nested.analysis)
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u001E").toByteArray())
        return HexFormat.of().formatHex(digest)
    }
}
