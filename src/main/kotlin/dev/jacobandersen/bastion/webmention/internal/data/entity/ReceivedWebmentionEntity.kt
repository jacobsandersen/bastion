package dev.jacobandersen.bastion.webmention.internal.data.entity

import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.microformats2.Mf2Object
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "received_webmentions")
class ReceivedWebmentionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false)
    var postId: UUID,
    @Column(nullable = false)
    var sourceUrl: String,
    @Column(nullable = false)
    var targetUrl: String,
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    var state: ReceivedWebmentionState,
    @Column(nullable = true)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    var interaction: WebmentionInteraction?,
    @Column(nullable = true)
    var authorName: String?,
    @Column(nullable = true)
    var authorUrl: String?,
    @Column(nullable = true)
    var authorPhoto: String?,
    @Column(nullable = true, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var contentText: List<String>? = null,
    @Column(nullable = true, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var contentHtml: List<String>? = null,
    @Column(nullable = true)
    @JdbcTypeCode(SqlTypes.JSON)
    var rawMf2: Mf2Object?,
    @Column(nullable = true)
    var url: String? = null,
    @Column(nullable = true, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var inReplyTo: List<String>? = null,
    @Column(nullable = true)
    var viaId: UUID? = null,
    @Column(nullable = true)
    var displayFingerprint: String? = null,
    @Column(nullable = true)
    var lastError: String?,
    @Column(nullable = false)
    var firstSeenAt: Instant,
    @Column(nullable = true)
    var verifiedAt: Instant?,
    @Column(nullable = false)
    var updatedAtUtc: Instant,
) {
    fun toDomain(): ReceivedWebmention =
        ReceivedWebmention(
            id = requireNotNull(id),
            postId = postId,
            sourceUrl = sourceUrl,
            targetUrl = targetUrl,
            state = state,
            interaction = interaction,
            authorName = authorName,
            authorUrl = authorUrl,
            authorPhoto = authorPhoto,
            contentText = contentText ?: emptyList(),
            contentHtml = contentHtml ?: emptyList(),
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
