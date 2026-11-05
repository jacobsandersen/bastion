package dev.jacobandersen.bastion.webmention.read

import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.service.ReceivedWebmentionService
import dev.jacobandersen.bastion.webmention.internal.service.ReceivedWebmentionAnalyzer
import dev.jacobandersen.bastion.webmention.internal.service.ThreadedWebmention
import dev.jacobandersen.bastion.webmention.internal.service.WebmentionThreadResolver
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WebmentionReadService(
    private val receivedWebmentionService: ReceivedWebmentionService,
    private val contentUrlMatcher: ContentUrlMatcher,
) {
    /** Verified webmentions for a post, oldest first, each with its place in the reply thread. */
    fun verifiedByPost(postId: UUID): List<WebmentionView> = resolve(receivedWebmentionService.verifiedByPost(postId)).map(::toView)

    fun countsForPost(postId: UUID): WebmentionCountsView = countsOf(resolve(receivedWebmentionService.verifiedByPost(postId)))

    fun countsByPostIds(postIds: Collection<UUID>): Map<UUID, WebmentionCountsView> = byPostIds(postIds).mapValues { it.value.counts }

    /**
     * Counts and verified webmentions (oldest first) for many posts from one query,
     * for feeds. Every requested post has an entry, empty when it has no webmentions.
     */
    fun byPostIds(postIds: Collection<UUID>): Map<UUID, PostWebmentionsView> {
        if (postIds.isEmpty()) return emptyMap()

        val byPost = receivedWebmentionService.verifiedByPostIds(postIds).groupBy { it.postId }
        return postIds.associateWith { postId ->
            val threaded = resolve(byPost[postId].orEmpty())
            PostWebmentionsView(
                counts = countsOf(threaded),
                webmentions = threaded.map(::toView),
            )
        }
    }

    private fun resolve(mentions: List<ReceivedWebmention>): List<ThreadedWebmention> =
        WebmentionThreadResolver.resolve(mentions, contentUrlMatcher::isOwnContentUrl)

    private fun countsOf(mentions: List<ThreadedWebmention>): WebmentionCountsView =
        WebmentionCountsView.of(
            mentions
                .groupingBy { (it.mention.interaction ?: WebmentionInteraction.MENTION).name }
                .eachCount(),
        )

    private fun toView(threaded: ThreadedWebmention): WebmentionView {
        val mention: ReceivedWebmention = threaded.mention
        return WebmentionView(
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
            url = threaded.url,
            urlSameOrigin = threaded.url?.let { ReceivedWebmentionAnalyzer.sameOrigin(mention.sourceUrl, it) } ?: false,
            inReplyTo = threaded.inReplyTo,
            parentSourceUrl = threaded.parent?.sourceUrl,
            viaSourceUrl = threaded.via?.sourceUrl,
        )
    }
}
