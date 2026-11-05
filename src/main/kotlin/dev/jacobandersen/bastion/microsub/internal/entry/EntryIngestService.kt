package dev.jacobandersen.bastion.microsub.internal.entry

import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import dev.jacobandersen.bastion.microsub.internal.moderation.ModerationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Stores normalized entries in a channel. Entries by blocked authors are
 * dropped (spec: future posts are not stored); muted authors are stored and
 * hidden at read time, so unmuting brings them back.
 */
@Service
class EntryIngestService(
    private val entries: EntryRepository,
    private val moderation: ModerationRepository,
) {
    /**
     * Stores a feed's items. Item ids (JSON Feed `id`, RSS `guid`) are only
     * unique within one feed, so the source key is scoped to [feedId].
     */
    @Transactional
    fun ingest(
        channelId: UUID,
        feedId: UUID,
        items: List<Jf2Item>,
        read: Boolean,
    ): Int {
        val now = Instant.now()
        var stored = 0
        items.forEach { item ->
            if (item.authorUrl != null && moderation.isBlocked(channelId, item.authorUrl)) return@forEach
            ingestOne(channelId, feedId, feedSourceKey(feedId, item), item, read, now)
            stored++
        }
        return stored
    }

    @Transactional
    fun ingestOne(
        channelId: UUID,
        feedId: UUID?,
        sourceKey: String,
        item: Jf2Item,
        read: Boolean,
        now: Instant,
    ) {
        // Future-dated entries would pin themselves to the top of the timeline.
        val published = item.published?.coerceAtMost(now) ?: now
        entries.upsert(channelId, feedId, sourceKey, item, published, item.published != null, read, now)
    }

    private fun feedSourceKey(
        feedId: UUID,
        item: Jf2Item,
    ) = "feed:$feedId:${item.sourceKey}"
}
