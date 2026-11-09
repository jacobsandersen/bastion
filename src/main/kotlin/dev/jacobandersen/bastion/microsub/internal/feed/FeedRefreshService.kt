package dev.jacobandersen.bastion.microsub.internal.feed

import dev.jacobandersen.bastion.microsub.events.EntryEvents
import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.entry.EntryIngestService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedDiscovery
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedLoad
import dev.jacobandersen.bastion.microsub.internal.fetch.ParsedFeed
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionService
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Fetches followed feeds. Every fetch runs under a claim on the feed row, so
 * the recurring poll and WebSub-triggered refreshes never fetch one feed
 * concurrently across replicas. Feeds with an active WebSub subscription are
 * only polled at the long fallback interval; failures back off exponentially.
 */
@Service
class FeedRefreshService(
    private val feeds: FeedRepository,
    private val discovery: FeedDiscovery,
    private val ingest: EntryIngestService,
    private val channels: ChannelRepository,
    private val events: EntryEvents,
    private val websub: WebsubSubscriptionService,
    private val jobScheduler: JobScheduler,
    private val config: MicrosubConfig,
) {
    /** JobRunr entry point for the recurring poll: claims due feeds and fetches each in its own job. */
    fun pollDue() {
        val now = Instant.now()
        val claimed = feeds.claimDue(now, now.plus(config.claimTimeout), config.pollBatchSize)
        claimed.forEach { id -> jobScheduler.enqueue { refreshClaimed(id) } }
        if (claimed.isNotEmpty()) logger.info { "Microsub poll claimed ${claimed.size} feeds" }
    }

    /**
     * On-demand refresh for a client opening a channel: fetches the channel's feeds that are stale, capped at the poll
     * batch size, each in its own job so the caller never waits on the network. Returns how many were started.
     */
    fun refreshStale(channelId: UUID): Int {
        val now = Instant.now()
        val claimed =
            feeds.claimStale(
                channelId,
                now.minus(config.refreshStaleAfter),
                now,
                now.plus(config.claimTimeout),
                config.pollBatchSize,
            )
        claimed.forEach { id -> jobScheduler.enqueue { refreshClaimed(id) } }
        return claimed.size
    }

    /** Asks for a fetch now, e.g. after a WebSub notification. */
    fun requestRefresh(feedId: UUID) {
        feeds.requestRefresh(feedId, Instant.now())
        jobScheduler.enqueue { refreshNow(feedId) }
    }

    /** JobRunr entry point: fetches unless another fetch holds the claim (it, or the next poll, picks the request up). */
    fun refreshNow(feedId: UUID) {
        val now = Instant.now()
        if (!feeds.claim(feedId, now, now.plus(config.claimTimeout))) return
        refreshClaimed(feedId)
    }

    /** JobRunr entry point: fetches a feed this caller has claimed and releases the claim. */
    fun refreshClaimed(feedId: UUID) {
        val feed = feeds.find(feedId) ?: return
        val load = discovery.load(feed.fetchUrl, feed.etag, feed.lastModified)
        val now = Instant.now()
        when (load) {
            is FeedLoad.NotModified -> {
                feeds.recordSuccess(feed.id, null, now.plus(successInterval(feed.subscriptionId)), now)
            }

            is FeedLoad.Failed -> {
                logger.info { "Fetching feed ${feed.fetchUrl} failed: ${load.reason}" }
                feeds.recordFailure(feed.id, load.reason, now.plus(backoff(feed.failureCount + 1)), now)
            }

            is FeedLoad.Loaded -> {
                val added = ingest.ingest(feed.channelId, feed.id, feed.url, load.feed.items, read = false)
                if (added > 0) channels.uidOf(feed.channelId)?.let { events.publish(it, added) }
                val subscriptionId = syncSubscription(feed, load.feed)
                feeds.recordSuccess(feed.id, metadataOf(load), now.plus(successInterval(subscriptionId)), now)
            }
        }
    }

    /**
     * Keeps the feed's WebSub subscription in line with the hub and `self`
     * URL the feed advertises, and returns the subscription id now in use.
     */
    fun syncSubscription(
        feed: Feed,
        parsed: ParsedFeed,
    ): UUID? {
        val hub = parsed.hubs.firstOrNull()
        val topic = parsed.self ?: feed.fetchUrl
        val current = feed.subscriptionId?.let(websub::find)
        if (hub != null && current != null && current.hub == hub && current.topic == topic) return current.id

        val next =
            hub?.let {
                runCatching { websub.subscribe(topic, it) }
                    .onFailure { e -> logger.warn { "Cannot subscribe to ${feed.fetchUrl} at hub $hub: ${e.message}" } }
                    .getOrNull()
            }
        if (next != feed.subscriptionId) {
            feeds.setSubscription(feed.id, next)
            feed.subscriptionId?.let(::releaseSubscription)
        }
        return next
    }

    /** Unsubscribes once no followed feed uses the subscription any more. */
    fun releaseSubscription(subscriptionId: UUID) {
        if (feeds.countBySubscription(subscriptionId) == 0) websub.unsubscribe(subscriptionId)
    }

    fun successInterval(subscriptionId: UUID?): Duration {
        val active = subscriptionId?.let(websub::find)?.state == WebsubSubscriptionState.ACTIVE
        return if (active) config.websubFallbackInterval else config.pollInterval
    }

    private fun backoff(failures: Int): Duration {
        val factor = 1L shl failures.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return config.pollInterval.multipliedBy(factor).coerceAtMost(config.maxBackoff)
    }

    companion object {
        private const val MAX_BACKOFF_EXPONENT = 10

        fun metadataOf(load: FeedLoad.Loaded) =
            FeedMetadata(
                fetchUrl = load.feed.url,
                format = load.feed.format,
                name = load.feed.name,
                photo = load.feed.photo,
                description = load.feed.description,
                hub = load.feed.hubs.firstOrNull(),
                selfUrl = load.feed.self,
                etag = load.etag,
                lastModified = load.lastModified,
            )
    }
}
