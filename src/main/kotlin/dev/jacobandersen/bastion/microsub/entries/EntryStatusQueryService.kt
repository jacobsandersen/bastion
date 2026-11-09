package dev.jacobandersen.bastion.microsub.entries

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** Read-side entry state for peer modules (the `reader` API). */
@Service
class EntryStatusQueryService(
    private val channels: ChannelRepository,
    private val entries: EntryRepository,
) {
    /**
     * Which of [ids] are gone from the channel [channelUid]: removed (a marked entry counts as gone even while its row
     * is kept), hard-deleted (block, webmention gone, unfollow cleanup), never in that channel, or not an entry id at
     * all. Returns the gone ids in request order without duplicates, or null when there is no such channel.
     */
    fun gone(
        channelUid: String,
        ids: List<String>,
    ): List<String>? {
        val channel = channels.findByUid(channelUid) ?: return null
        val requested = ids.map(String::trim).distinct()
        val parsed = requested.associateWith { runCatching { UUID.fromString(it) }.getOrNull() }
        val live = entries.liveIds(channel.id, parsed.values.filterNotNull())
        return requested.filter { parsed[it]?.let { id -> id in live } != true }
    }
}
