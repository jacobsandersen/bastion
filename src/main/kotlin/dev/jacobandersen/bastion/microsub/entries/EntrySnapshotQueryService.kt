package dev.jacobandersen.bastion.microsub.entries

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import dev.jacobandersen.bastion.microsub.internal.entry.toJf2
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * A copy of an entry as a timeline serves it, with the columns derived from it. [jf2] is the served item without
 * `_is_read`: its data plus `_id`, `_source` and, for an undated entry, `published`.
 */
data class EntrySnapshot(
    val jf2: Map<String, Any?>,
    val searchText: String,
    val published: Instant,
    val url: String?,
    val authorUrl: String?,
    val categories: List<String>,
    val kinds: Set<String>,
    /** The followed feed's url, or null when the entry has no followed feed. */
    val feedUrl: String?,
)

/** Reads an entry in full so a peer module (the `reader` API) can keep its own copy of it. */
@Service
class EntrySnapshotQueryService(
    private val channels: ChannelRepository,
    private val entries: EntryRepository,
) {
    /**
     * The entry [entryId] of the channel [channelUid], or null when the channel does not exist, [entryId] is not an
     * entry id, or the entry is not in that channel (hard-deleted, or never there). An entry the owner deleted is still
     * returned while its row is kept (see the deleted list), which is what lets a client rescue it.
     */
    @Transactional(readOnly = true)
    fun snapshot(
        channelUid: String,
        entryId: String,
    ): EntrySnapshot? {
        val channel = channels.findByUid(channelUid) ?: return null
        val id = runCatching { UUID.fromString(entryId.trim()) }.getOrNull() ?: return null
        val row = entries.findInChannel(channel.id, id) ?: return null
        val derived = Jf2Item.of(row.data.filterValues { it != null }.mapValues { requireNotNull(it.value) })
        return EntrySnapshot(
            jf2 = row.toJf2() - "_is_read",
            searchText = derived.searchText,
            published = row.published,
            url = derived.url,
            authorUrl = derived.authorUrl,
            categories = derived.categories,
            kinds = derived.kinds,
            feedUrl = row.source?.url,
        )
    }
}
