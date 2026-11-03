package dev.jacobandersen.bastion.webmention.read

import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.service.ReceivedWebmentionService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WebmentionReadService(
    private val receivedWebmentionService: ReceivedWebmentionService,
) {
    fun verifiedByPost(postId: UUID): List<WebmentionView> = receivedWebmentionService.verifiedByPost(postId).map(::toView)

    fun countsForPost(postId: UUID): WebmentionCountsView = countsOf(receivedWebmentionService.verifiedByPost(postId))

    fun countsByPostIds(postIds: Collection<UUID>): Map<UUID, WebmentionCountsView> = byPostIds(postIds).mapValues { it.value.counts }

    /**
     * Counts and verified webmentions (oldest first) for many posts from one query,
     * for feeds. Every requested post has an entry, empty when it has no webmentions.
     */
    fun byPostIds(postIds: Collection<UUID>): Map<UUID, PostWebmentionsView> {
        if (postIds.isEmpty()) return emptyMap()

        val byPost = receivedWebmentionService.verifiedByPostIds(postIds).groupBy { it.postId }
        return postIds.associateWith { postId ->
            val mentions = byPost[postId].orEmpty()
            PostWebmentionsView(
                counts = countsOf(mentions),
                webmentions = mentions.sortedBy { it.firstSeenAt }.map(::toView),
            )
        }
    }

    private fun countsOf(mentions: List<ReceivedWebmention>): WebmentionCountsView =
        WebmentionCountsView.of(
            mentions
                .groupingBy { (it.interaction ?: WebmentionInteraction.MENTION).name }
                .eachCount(),
        )

    private fun toView(mention: ReceivedWebmention): WebmentionView =
        WebmentionView(
            sourceUrl = mention.sourceUrl,
            targetUrl = mention.targetUrl,
            interaction = (mention.interaction ?: WebmentionInteraction.MENTION).name,
            authorName = mention.authorName,
            authorUrl = mention.authorUrl,
            authorPhoto = mention.authorPhoto,
            contentText = mention.contentText,
            contentHtml = mention.contentHtml,
            firstSeenAt = mention.firstSeenAt,
            verifiedAt = requireNotNull(mention.verifiedAt) { "verified webmention ${mention.id} missing verifiedAt" },
        )
}
