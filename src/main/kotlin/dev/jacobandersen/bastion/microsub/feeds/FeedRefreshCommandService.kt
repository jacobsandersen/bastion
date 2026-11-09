package dev.jacobandersen.bastion.microsub.feeds

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import org.springframework.stereotype.Service

/** Feed refresh commands for peer modules (the `reader` API). */
@Service
class FeedRefreshCommandService(
    private val channels: ChannelRepository,
    private val refresh: FeedRefreshService,
) {
    /**
     * Starts a background fetch of the channel's stale feeds and returns how many were started, or null when there is
     * no channel [channelUid]. Shares [FeedRefreshService.refreshStale] with the legacy `/microsub/refresh` route.
     */
    fun refreshStale(channelUid: String): Int? {
        val channel = channels.findByUid(channelUid) ?: return null
        return refresh.refreshStale(channel.id)
    }
}
