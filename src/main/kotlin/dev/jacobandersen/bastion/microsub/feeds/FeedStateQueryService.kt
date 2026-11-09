package dev.jacobandersen.bastion.microsub.feeds

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.feed.FeedDetailsMapper
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRepository
import org.springframework.stereotype.Service

/** Read-side access to followed-feed state for peer modules (the `reader` API). */
@Service
class FeedStateQueryService(
    private val channels: ChannelRepository,
    private val feeds: FeedRepository,
    private val details: FeedDetailsMapper,
) {
    /** The feeds followed in the channel [channelUid], or null when there is no such channel. */
    fun list(channelUid: String): List<FeedDetails>? {
        val channel = channels.findByUid(channelUid) ?: return null
        return feeds.listByChannel(channel.id).map(details::of)
    }
}
