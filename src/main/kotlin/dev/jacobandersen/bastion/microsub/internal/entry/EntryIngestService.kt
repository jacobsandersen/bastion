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
    private val deletion: EntryDeletionService,
) {
    /**
     * Stores a feed's items. Item ids (JSON Feed `id`, RSS `guid`) are only
     * unique within one feed, so the source key is scoped to the feed's
     * followed [feedUrl], which is unique per channel and survives an
     * unfollow and re-follow, unlike [feedId]. Entries the owner deleted are left as they are, apart from noting
     * that the feed still lists them. Returns how many entries were new.
     */
    @Transactional
    fun ingest(
        channelId: UUID,
        feedId: UUID,
        feedUrl: String,
        items: List<Jf2Item>,
        read: Boolean,
    ): Int = store(channelId, feedId, feedUrl, items, read, Instant.now())

    /**
     * [ingest] for a fetch that loaded the feed document: afterwards, deleted entries the document no longer lists
     * are permanently deleted. A document with no items deletes nothing, because an empty or truncated response is
     * indistinguishable from a feed that dropped everything and would otherwise let deleted items come back.
     */
    @Transactional
    fun ingestFetched(
        channelId: UUID,
        feedId: UUID,
        feedUrl: String,
        items: List<Jf2Item>,
        read: Boolean,
    ): Int {
        val now = Instant.now()
        val added = store(channelId, feedId, feedUrl, items, read, now)
        if (items.isNotEmpty()) deletion.deleteUnlisted(feedId, now)
        return added
    }

    private fun store(
        channelId: UUID,
        feedId: UUID,
        feedUrl: String,
        items: List<Jf2Item>,
        read: Boolean,
        now: Instant,
    ): Int {
        var added = 0
        val listed = mutableListOf<String>()
        items.forEach { item ->
            val key = feedSourceKey(feedUrl, item)
            listed += key
            if (item.authorUrl != null && moderation.isBlocked(channelId, item.authorUrl)) return@forEach
            if (ingestOne(channelId, feedId, key, item, read, now)) added++
        }
        entries.touchMarked(channelId, feedId, listed, now)
        return added
    }

    @Transactional
    fun ingestOne(
        channelId: UUID,
        feedId: UUID?,
        sourceKey: String,
        item: Jf2Item,
        read: Boolean,
        now: Instant,
    ): Boolean {
        // Future-dated entries would pin themselves to the top of the timeline.
        val published = item.published?.coerceAtMost(now) ?: now
        return entries.upsert(channelId, feedId, sourceKey, item, published, item.published != null, read, now)
    }

    private fun feedSourceKey(
        feedUrl: String,
        item: Jf2Item,
    ) = "feed:$feedUrl:${item.sourceKey}"
}
