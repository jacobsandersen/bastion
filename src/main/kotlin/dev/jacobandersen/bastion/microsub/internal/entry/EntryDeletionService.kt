package dev.jacobandersen.bastion.microsub.internal.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Deleting an entry leaves a marker (the row with `removed_at` set) so an item its feed still lists is not stored
 * again as a new unread entry. The marker keeps the entry's content, so it can still be viewed, until a fetch shows
 * that the feed no longer lists the item; then [deleteUnlisted] removes the row for good.
 */
@Service
class EntryDeletionService(
    private val entries: EntryRepository,
) {
    /** Marks the entries deleted. Unknown, foreign and already deleted ids are skipped. Returns how many were marked. */
    @Transactional
    fun tombstone(
        channelId: UUID,
        ids: Collection<UUID>,
        now: Instant = Instant.now(),
    ): Int = entries.markRemoved(channelId, ids, now)

    /**
     * Permanently deletes the feed's markers that the fetch made at [fetchedAt] did not list. The caller must only
     * pass a fetch that loaded and parsed the feed document: a failed or not-modified fetch proves nothing about
     * what the feed lists.
     */
    @Transactional
    fun deleteUnlisted(
        feedId: UUID,
        fetchedAt: Instant,
    ): Int = entries.deleteUnlistedMarkers(feedId, fetchedAt)
}
