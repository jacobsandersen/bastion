package dev.jacobandersen.bastion.microsub.internal.feed

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.entry.EntryIngestService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedDiscovery
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedLoad
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * `action=follow` and `action=unfollow`. Following fetches the feed once (so
 * a URL with no feed is rejected up front), backfills its current entries and
 * subscribes over WebSub when the feed advertises a hub. Unfollowing keeps
 * the entries already in the channel; retention removes them once read.
 */
@Service
class FollowService(
    private val channels: ChannelService,
    private val feeds: FeedRepository,
    private val discovery: FeedDiscovery,
    private val ingest: EntryIngestService,
    private val refresh: FeedRefreshService,
    private val config: MicrosubConfig,
) {
    fun list(channelUid: String?): List<Map<String, Any>> = feeds.listByChannel(channels.require(channelUid).id).map(::view)

    fun follow(
        channelUid: String?,
        url: String?,
    ): Map<String, Any> {
        val channel = channels.require(channelUid)
        val normalized = normalize(url)
        feeds.findByChannelAndUrl(channel.id, normalized)?.let { return view(it) }

        val load =
            when (val result = discovery.load(normalized, null, null)) {
                is FeedLoad.Loaded -> result
                is FeedLoad.NotModified -> throw MicrosubError.invalidRequest("Could not load a feed from $normalized")
                is FeedLoad.Failed -> throw MicrosubError.invalidRequest("Could not load a feed from $normalized: ${result.reason}")
            }

        val now = Instant.now()
        val feed =
            try {
                feeds.insert(channel.id, normalized, FeedRefreshService.metadataOf(load), now.plus(config.pollInterval), now)
            } catch (_: DuplicateKeyException) {
                return feeds.findByChannelAndUrl(channel.id, normalized)?.let(::view)
                    ?: throw MicrosubError.invalidRequest("Concurrent follow of $normalized, please retry")
            }
        ingest.ingest(channel.id, feed.id, load.feed.items, read = config.backfillAsRead)
        refresh.syncSubscription(feed, load.feed)
        return view(feed)
    }

    fun unfollow(
        channelUid: String?,
        url: String?,
    ) {
        val channel = channels.require(channelUid)
        val feed = feeds.findByChannelAndUrl(channel.id, normalize(url)) ?: return
        feeds.delete(feed.id)
        feed.subscriptionId?.let(refresh::releaseSubscription)
    }

    private fun normalize(url: String?): String =
        url?.let(MicrosubUrls::fromUserInput) ?: throw MicrosubError.invalidRequest("The 'url' parameter must be a URL")

    private fun view(feed: Feed): Map<String, Any> =
        jf2 {
            this["type"] = "feed"
            this["url"] = feed.url
            this["name"] = feed.name
            this["photo"] = feed.photo
            this["description"] = feed.description
        }
}
