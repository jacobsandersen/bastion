package dev.jacobandersen.bastion.webmention.internal.data.domain

import dev.jacobandersen.bastion.webmention.internal.data.entity.ReceivedWebmentionEntity
import dev.jacobandersen.microformats2.Mf2Object
import java.time.Instant
import java.util.UUID

data class ReceivedWebmention(
    val id: UUID,
    val postId: UUID,
    val sourceUrl: String,
    val targetUrl: String,
    val state: ReceivedWebmentionState,
    val interaction: WebmentionInteraction?,
    val authorName: String?,
    val authorUrl: String?,
    val authorPhoto: String?,
    val contentText: List<String>,
    val contentHtml: List<String>,
    val rawMf2: Mf2Object?,
    val url: String?,
    val inReplyTo: List<String>?,
    val viaId: UUID?,
    val displayFingerprint: String?,
    val lastError: String?,
    val firstSeenAt: Instant,
    val verifiedAt: Instant?,
    val updatedAtUtc: Instant,
) {
    fun toEntity(): ReceivedWebmentionEntity =
        ReceivedWebmentionEntity(
            id = id,
            postId = postId,
            sourceUrl = sourceUrl,
            targetUrl = targetUrl,
            state = state,
            interaction = interaction,
            authorName = authorName,
            authorUrl = authorUrl,
            authorPhoto = authorPhoto,
            contentText = contentText,
            contentHtml = contentHtml,
            rawMf2 = rawMf2,
            url = url,
            inReplyTo = inReplyTo,
            viaId = viaId,
            displayFingerprint = displayFingerprint,
            lastError = lastError,
            firstSeenAt = firstSeenAt,
            verifiedAt = verifiedAt,
            updatedAtUtc = updatedAtUtc,
        )
}
