package dev.jacobandersen.bastion.microsub.stats

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.stats.StatsRepository
import dev.jacobandersen.bastion.microsub.internal.stats.StorageRow
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** What a channel or feed holds. [entries] excludes entries marked deleted, which are counted in [removed]. */
data class StorageUsage(
    val entries: Long,
    val unread: Long,
    val removed: Long,
    /** Size of the rows, deleted markers included. */
    val bytes: Long,
)

data class ChannelStorage(
    val uid: String,
    val usage: StorageUsage,
)

data class FeedStorage(
    val channel: String,
    val url: String,
    val usage: StorageUsage,
)

/** A configured limit that is exceeded. [code] is `bytes_high` or `entries_high`. Informational: nothing acts on it. */
data class StorageWarning(
    val code: String,
    val threshold: Long,
    val value: Long,
)

/**
 * Microsub's footprint. Per-channel and per-feed `bytes` are row sizes and do not add up to [onDiskBytes], which also
 * counts indexes and free space; [databaseBytes] is the whole database (null when it cannot be read). Counts cover
 * every channel, `notifications` included. Entries without a followed feed count towards their channel only.
 */
data class StorageReport(
    val computedAt: Instant,
    val ttl: Duration,
    val databaseBytes: Long?,
    val onDiskBytes: Long,
    val total: StorageUsage,
    val channels: List<ChannelStorage>,
    val feeds: List<FeedStorage>,
    val warnings: List<StorageWarning>,
)

/**
 * Read-side storage report for peer modules (the `reader` API). The report scans the entries table, so each replica
 * keeps its snapshot for `bastion.microsub.storage-cache-ttl` (5 minutes) and recomputes it for one caller at a time.
 */
@Service
class StorageQueryService internal constructor(
    private val channels: ChannelRepository,
    private val stats: StatsRepository,
    private val config: MicrosubConfig,
    private val clock: Clock,
) {
    @Autowired
    constructor(channels: ChannelRepository, stats: StatsRepository, config: MicrosubConfig) :
        this(channels, stats, config, Clock.systemUTC())

    @Volatile
    private var snapshot: StorageReport? = null

    fun report(): StorageReport = fresh() ?: synchronized(this) { fresh() ?: compute().also { snapshot = it } }

    private fun fresh(): StorageReport? = snapshot?.takeIf { Duration.between(it.computedAt, clock.instant()) < config.storageCacheTtl }

    private fun compute(): StorageReport {
        val now = clock.instant()
        val rows = stats.storageByFeed()
        val onDisk = stats.onDiskBytes()
        val channelList = channels.list()
        val byChannel = rows.groupBy { it.channelId }
        val byFeed = rows.filter { it.feedId != null }.associateBy { it.feedId }
        val total = usage(rows)
        return StorageReport(
            computedAt = now,
            ttl = config.storageCacheTtl,
            databaseBytes =
                runCatching { stats.databaseBytes() }
                    .onFailure {
                        logger.warn(
                            it,
                        ) { "Could not read the database size" }
                    }.getOrNull(),
            onDiskBytes = onDisk,
            total = total,
            channels = channelList.map { ChannelStorage(it.uid, usage(byChannel[it.id].orEmpty())) },
            feeds = stats.followedFeeds().map { FeedStorage(it.channelUid, it.url, usage(listOfNotNull(byFeed[it.id]))) },
            warnings = warnings(onDisk, total.entries),
        )
    }

    private fun usage(rows: List<StorageRow>) =
        StorageUsage(rows.sumOf { it.entries }, rows.sumOf { it.unread }, rows.sumOf { it.removed }, rows.sumOf { it.bytes })

    private fun warnings(
        onDiskBytes: Long,
        entries: Long,
    ): List<StorageWarning> =
        listOfNotNull(
            config.storageWarnBytes?.takeIf { it > 0 && onDiskBytes > it }?.let { StorageWarning("bytes_high", it, onDiskBytes) },
            config.storageWarnEntries?.takeIf { it > 0 && entries > it }?.let { StorageWarning("entries_high", it, entries) },
        )
}
