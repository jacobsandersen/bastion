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
import dev.jacobandersen.bastion.webmention.internal.service.ReceivedWebmentionAnalyzer
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
        val existing =
            repository.findBySourceUrlAndPostId(sourceUrl, postId)
                ?: matching(repository.findByPostId(postId), sourceUrl).firstOrNull { it.viaId != null }
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
            existing.sourceUrl = sourceUrl
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
        entity.lastError = null
        entity.verifiedAt = now
        entity.updatedAtUtc = now
        val saved = repository.save(entity)
        val keys = keysOf(saved)
        val copies =
            repository.findByPostId(postId).filter { row ->
                row.viaId != null && row.id != saved.id && keysOf(row).any { it in keys }
            }
        if (copies.isNotEmpty()) repository.deleteAll(copies)

        val shown = replaceNested(saved, analysis.nested, now)
        saved.displayFingerprint = fingerprint(analysis.copy(nested = shown))
        repository.save(saved)

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
     * Upserts the nested responses displayed by [via] and releases the ones it
     * no longer displays. Rows are matched on source URL or canonical URL. A
     * response with a direct webmention is hidden behind
     * it and left alone; one already owned by another displaying source stays
     * with that source. Returns the nested responses the post actually shows
     * through [via], which is what its fingerprint covers.
     */
    private fun replaceNested(
        via: ReceivedWebmentionEntity,
        nested: List<NestedResponse>,
        now: Instant,
    ): List<NestedResponse> {
        val viaId = requireNotNull(via.id)
        val owned = mutableSetOf<UUID>()
        val shown = mutableListOf<NestedResponse>()
        val onPost = repository.findByPostId(via.postId)

        nested.forEach { response ->
            val matches = matching(onPost, response.url)
            if (matches.any { it.viaId == null }) return@forEach
            val existing = matches.firstOrNull { it.viaId == viaId } ?: matches.firstOrNull()
            shown += response
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

        releaseNested(repository.findByViaId(viaId).filterNot { requireNotNull(it.id) in owned }, viaId, now)
        return shown
    }

    /**
     * Releases nested rows whose owning source [formerOwnerId] no longer
     * displays them. A row hidden behind a direct webmention is deleted. A row
     * another verified source on the post still displays (per its stored
     * microformats) moves to that source; the rest are deleted. This keeps a response two sources display from disappearing
     * when only one of them drops it.
     */
    private fun releaseNested(
        rows: List<ReceivedWebmentionEntity>,
        formerOwnerId: UUID,
        now: Instant,
    ) {
        if (rows.isEmpty()) return

        val directKeys =
            repository
                .findByPostId(rows.first().postId)
                .filter { it.viaId == null }
                .flatMap(::keysOf)
                .toSet()
        val heirs by lazy {
            repository
                .findByPostIdAndState(rows.first().postId, VERIFIED)
                .filter { it.viaId == null && it.id != formerOwnerId && it.rawMf2 != null }
                .map { candidate ->
                    val displayed =
                        ReceivedWebmentionAnalyzer
                            .nestedResponses(requireNotNull(candidate.rawMf2), candidate.sourceUrl) { false }
                            .map { ReceivedWebmentionAnalyzer.urlKey(it.url) }
                            .toSet()
                    candidate to displayed
                }
        }

        val orphans =
            rows.filter { row ->
                val keys = keysOf(row)
                if (keys.any { it in directKeys }) return@filter true
                val heir = heirs.firstOrNull { (_, displayed) -> keys.any { it in displayed } }?.first
                if (heir != null) {
                    row.viaId = heir.id
                    row.targetUrl = heir.targetUrl
                    row.updatedAtUtc = now
                    repository.save(row)
                }
                heir == null
            }
        if (orphans.isNotEmpty()) repository.deleteAll(orphans)
    }

    /** The rows among [rows] that are the response at [url], by source URL or canonical URL. */
    private fun matching(
        rows: List<ReceivedWebmentionEntity>,
        url: String,
    ): List<ReceivedWebmentionEntity> {
        val key = ReceivedWebmentionAnalyzer.urlKey(url)
        return rows.filter { key in keysOf(it) }
    }

    private fun keysOf(row: ReceivedWebmentionEntity): Set<String> =
        listOfNotNull(row.sourceUrl, row.url).map(ReceivedWebmentionAnalyzer::urlKey).toSet()

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
            entity.id?.let { id -> releaseNested(repository.findByViaId(id), id, now) }
        }
        if (state == DELETED) {
            entity.interaction = null
            entity.authorName = null
            entity.authorUrl = null
            entity.authorPhoto = null
            entity.contentText = null
            entity.contentHtml = null
            entity.rawMf2 = null
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
