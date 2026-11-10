package dev.jacobandersen.bastion.microsub.stats

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.stats.StatsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ChannelUnread(
    val uid: String,
    val unread: Int,
)

data class FeedUnread(
    val channel: String,
    val url: String,
    val unread: Int,
)

/**
 * Unread entries on the server. [total] leaves out the `notifications` channel (webmentions are not reading); [channels]
 * lists every channel, `notifications` included, with the numbers `action=channels` reports. Entries without a followed
 * feed count towards their channel but appear under no feed.
 */
data class UnreadTotals(
    val total: Int,
    val channels: List<ChannelUnread>,
    val feeds: List<FeedUnread>,
)

/** Read-side unread totals for peer modules (the `reader` API). Entries marked deleted are never unread. */
@Service
class UnreadQueryService(
    private val channels: ChannelRepository,
    private val stats: StatsRepository,
) {
    @Transactional(readOnly = true)
    fun totals(): UnreadTotals {
        val rows = stats.unreadByFeed()
        val byChannel = rows.groupBy { it.channelId }.mapValues { (_, channelRows) -> channelRows.sumOf { it.unread } }
        val byFeed = rows.filter { it.feedId != null }.associate { it.feedId to it.unread }
        val channelList = channels.list().map { ChannelUnread(it.uid, byChannel[it.id] ?: 0) }
        return UnreadTotals(
            total = channelList.filter { it.uid != ChannelService.NOTIFICATIONS }.sumOf { it.unread },
            channels = channelList,
            feeds = stats.followedFeeds().map { FeedUnread(it.channelUid, it.url, byFeed[it.id] ?: 0) },
        )
    }
}
