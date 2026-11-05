package dev.jacobandersen.bastion.microsub.internal.listener

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.entry.EntryIngestService
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRepository
import dev.jacobandersen.bastion.microsub.internal.jf2.HtmlSanitizer
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationRepository
import dev.jacobandersen.bastion.webmention.events.ReceivedWebmentionChangedEvent
import dev.jacobandersen.bastion.webmention.read.ReceivedWebmentionLookup
import dev.jacobandersen.bastion.webmention.read.WebmentionReadService
import dev.jacobandersen.bastion.webmention.read.WebmentionView
import dev.jacobandersen.bastion.websub.events.WebsubContentNotifiedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** A hub pushed new content: re-fetch every followed feed on that subscription. */
@Component
class WebsubContentListener(
    private val feeds: FeedRepository,
    private val refresh: FeedRefreshService,
) {
    @ApplicationModuleListener
    fun on(event: WebsubContentNotifiedEvent) {
        val subscribed = feeds.listBySubscription(event.subscriptionId)
        logger.info { "WebSub content for ${event.topic}; refreshing ${subscribed.size} feeds" }
        subscribed.forEach { refresh.requestRefresh(it.id) }
    }
}

/**
 * Mirrors directly received webmentions into the `notifications` channel.
 * Loads the latest state rather than trusting the event, so a webmention that
 * is gone is removed and replays converge. A webmention still being
 * re-verified keeps its entry; settling announces any display change. With no
 * entry yet, the event fails so the resubmission job retries it after
 * verification settles, since settling unchanged publishes nothing.
 */
@Component
class WebmentionNotificationListener(
    private val webmentions: WebmentionReadService,
    private val channels: ChannelRepository,
    private val entries: EntryRepository,
    private val ingest: EntryIngestService,
    private val moderation: ModerationRepository,
) {
    @ApplicationModuleListener
    fun on(event: ReceivedWebmentionChangedEvent) {
        val channel = channels.findByUid(ChannelService.NOTIFICATIONS) ?: return
        val key = sourceKey(event.webmentionId)
        val item =
            when (val lookup = webmentions.receivedById(event.webmentionId)) {
                is ReceivedWebmentionLookup.Displayed -> {
                    toItem(lookup.webmention)
                }

                ReceivedWebmentionLookup.Unsettled -> {
                    if (entries.existsBySourceKey(channel.id, key)) return
                    throw IllegalStateException("Webmention ${event.webmentionId} is still being verified; retrying later")
                }

                ReceivedWebmentionLookup.Gone -> {
                    null
                }
            }
        if (item == null || (item.authorUrl != null && moderation.isBlocked(channel.id, item.authorUrl))) {
            entries.deleteBySourceKey(channel.id, key)
            return
        }
        ingest.ingestOne(channel.id, null, key, item, read = false, now = Instant.now())
    }

    companion object {
        fun sourceKey(webmentionId: UUID) = "webmention:$webmentionId"

        private val INTERACTION_PROPERTY =
            mapOf(
                "LIKE" to "like-of",
                "REPOST" to "repost-of",
                "BOOKMARK" to "bookmark-of",
                "REPLY" to "in-reply-to",
                "RSVP" to "in-reply-to",
                "MENTION" to "mention-of",
            )

        fun toItem(view: WebmentionView): Jf2Item {
            val url = view.url?.takeIf { view.urlSameOrigin } ?: view.sourceUrl
            return Jf2Item.of(
                jf2 {
                    this["type"] = "entry"
                    this["url"] = url
                    this["published"] = Jf2Item.formatInstant(view.firstSeenAt)
                    this["author"] =
                        jf2 {
                            this["type"] = "card"
                            this["name"] = view.authorName
                            this["url"] = view.authorUrl
                            this["photo"] = view.authorPhoto
                        }.takeIf { it.size > 1 }
                    this["content"] = HtmlSanitizer.content(view.contentHtml.firstOrNull(), view.contentText.firstOrNull(), url)
                    this[INTERACTION_PROPERTY[view.interaction] ?: "mention-of"] = listOf(view.targetUrl)
                },
            )
        }
    }
}
