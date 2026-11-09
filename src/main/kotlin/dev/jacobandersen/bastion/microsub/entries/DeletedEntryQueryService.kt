package dev.jacobandersen.bastion.microsub.entries

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import dev.jacobandersen.bastion.microsub.internal.entry.RemovedCursor
import dev.jacobandersen.bastion.microsub.internal.entry.toJf2
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A page of deleted entries, most recently deleted first. [after] is the cursor of the next page, when there is one. */
data class DeletedEntryPage(
    /** Jf2 items as served in a timeline (`_id`, `_is_read`, `_source`) plus `_deleted_at`. */
    val items: List<Map<String, Any?>>,
    val after: String?,
)

/** Read-side access to deleted entries for peer modules (the `reader` API). */
@Service
class DeletedEntryQueryService(
    private val channels: ChannelRepository,
    private val entries: EntryRepository,
    private val config: MicrosubConfig,
) {
    /**
     * The channel's deleted entries, or null when there is no channel [channelUid]. [limit] is clamped to the page
     * size Microsub allows; an unusable [after] cursor starts from the most recently deleted entry.
     */
    @Transactional(readOnly = true)
    fun list(
        channelUid: String,
        after: String?,
        limit: Int?,
    ): DeletedEntryPage? {
        val channel = channels.findByUid(channelUid) ?: return null
        val size = (limit ?: config.defaultLimit).coerceIn(1, config.maxLimit)
        val rows = entries.pageRemoved(channel.id, RemovedCursor.decode(after), size)
        val page = rows.take(size)
        val next = if (rows.size > size) page.last().let { RemovedCursor(requireNotNull(it.removedAt), it.id).encode() } else null
        val items = page.map { row -> row.toJf2() + ("_deleted_at" to Jf2Item.formatInstant(requireNotNull(row.removedAt))) }
        return DeletedEntryPage(items, next)
    }
}
