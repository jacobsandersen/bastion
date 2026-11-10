package dev.jacobandersen.bastion.microsub.entries

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.entry.BulkDeleteFilter
import dev.jacobandersen.bastion.microsub.internal.entry.BulkDeleteRepository
import dev.jacobandersen.bastion.microsub.internal.entry.EntryDeletionService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** Which entries a bulk delete covers. See [BulkDeleteFilter]; [channel] is a channel uid, or null or `global` for all. */
data class BulkDeleteScope(
    val channel: String?,
    val feed: String?,
    /** Also cover unread entries. Off covers read entries only. */
    val includeUnread: Boolean,
    val publishedBefore: Instant?,
    val unfollowed: Boolean,
)

data class BulkDeleteChannelPreview(
    val uid: String,
    val matched: Int,
)

/** What a scope matches now. [permanent] of the [matched] entries would be deleted for good, the rest marked deleted. */
data class BulkDeletePreview(
    val matched: Int,
    val unread: Int,
    val permanent: Int,
    /** Stored size of the matched rows. */
    val bytes: Long,
    val oldest: Instant?,
    val newest: Instant?,
    val byChannel: List<BulkDeleteChannelPreview>,
)

/** The result of one chunk. [remaining] is what the scope still matched after it. */
data class BulkDeleteChunk(
    val deleted: Int,
    val remaining: Int,
)

/** Why a bulk delete was not run. */
sealed class BulkDeleteRefusal(
    message: String,
) : RuntimeException(message) {
    class ChannelNotFound(
        val uid: String,
    ) : BulkDeleteRefusal("Channel '$uid' does not exist")

    /** More entries match than the caller was told about. */
    class ScopeChanged(
        val matched: Int,
        val expected: Int,
    ) : BulkDeleteRefusal("$matched entries match now but only $expected were expected")

    /** Another bulk delete is running. */
    class Busy : BulkDeleteRefusal("Another bulk delete is running")
}

/**
 * Deletes many entries at once for peer modules (the `reader` API), in chunks of at most [CHUNK_SIZE]. Every entry goes
 * through the same rule as a single delete: an entry a feed may still list becomes a marker (see
 * [EntryDeletionService]), so it is not added again; an entry whose feed was unfollowed, which nothing lists, is
 * deleted for good. Callers decide who may do this and that the owner saw the [preview] first.
 */
@Service
class EntryBulkDeleteService(
    private val channels: ChannelRepository,
    private val repository: BulkDeleteRepository,
    private val deletion: EntryDeletionService,
) {
    /** What [scope] matches now. */
    @Transactional(readOnly = true)
    fun preview(scope: BulkDeleteScope): BulkDeletePreview {
        val totals = repository.totals(filterOf(scope))
        return BulkDeletePreview(
            matched = totals.sumOf { it.matched },
            unread = totals.sumOf { it.unread },
            permanent = totals.sumOf { it.permanent },
            bytes = totals.sumOf { it.bytes },
            oldest = totals.minOfOrNull { it.oldest },
            newest = totals.maxOfOrNull { it.newest },
            byChannel = totals.map { BulkDeleteChannelPreview(it.channelUid, it.matched) },
        )
    }

    /**
     * Deletes up to [CHUNK_SIZE] entries of [scope]. Refuses, deleting nothing, when more than [expected] entries match
     * now, or when another bulk delete runs. Fewer is fine, so running it again after a crash only deletes what is
     * left. [actor] (the client that asked) is logged with the outcome.
     */
    @Transactional
    fun deleteChunk(
        scope: BulkDeleteScope,
        expected: Int,
        actor: String,
    ): BulkDeleteChunk {
        if (!repository.tryLock()) throw BulkDeleteRefusal.Busy()
        val filter = filterOf(scope)
        val matched = repository.totals(filter).sumOf { it.matched }
        if (matched > expected) {
            logger.warn { "Bulk delete by $actor refused: $matched entries match $scope but $expected were expected" }
            throw BulkDeleteRefusal.ScopeChanged(matched, expected)
        }

        val candidates = repository.lockCandidates(filter, CHUNK_SIZE)
        val now = Instant.now()
        val permanent = repository.delete(candidates.filter { it.orphaned }.map { it.id })
        val marked =
            candidates
                .filterNot { it.orphaned }
                .groupBy({ it.channelId }, { it.id })
                .entries
                .sumOf { (channelId, ids) -> deletion.tombstone(channelId, ids, now) }
        val deleted = permanent + marked
        val remaining = (matched - deleted).coerceAtLeast(0)
        logger.info {
            "Bulk delete by $actor of $scope: $matched matched, $deleted deleted ($permanent permanently, $marked marked), $remaining remaining"
        }
        return BulkDeleteChunk(deleted, remaining)
    }

    private fun filterOf(scope: BulkDeleteScope): BulkDeleteFilter {
        val uid = scope.channel?.takeUnless { it == GLOBAL }
        val channelId = uid?.let { (channels.findByUid(it) ?: throw BulkDeleteRefusal.ChannelNotFound(it)).id }
        return BulkDeleteFilter(channelId, scope.feed, scope.includeUnread, scope.publishedBefore, scope.unfollowed)
    }

    companion object {
        const val CHUNK_SIZE = 5_000
        const val GLOBAL = "global"
    }
}
