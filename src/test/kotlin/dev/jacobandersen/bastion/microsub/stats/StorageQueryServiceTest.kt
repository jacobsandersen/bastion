package dev.jacobandersen.bastion.microsub.stats

import dev.jacobandersen.bastion.microsub.internal.channel.Channel
import dev.jacobandersen.bastion.microsub.internal.channel.ChannelRepository
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.stats.StatsRepository
import dev.jacobandersen.bastion.microsub.internal.stats.StorageRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StorageQueryServiceTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    private val channelId = UUID.randomUUID()
    private val channels = mock<ChannelRepository>()
    private val stats = mock<StatsRepository>()
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))

    init {
        whenever(channels.list()).thenReturn(listOf(Channel(channelId, "home", "Home", 1)))
        whenever(stats.storageByFeed()).thenReturn(listOf(StorageRow(channelId, null, 10, 4, 2, 1000)))
        whenever(stats.followedFeeds()).thenReturn(emptyList())
        whenever(stats.onDiskBytes()).thenReturn(5_000)
        whenever(stats.databaseBytes()).thenReturn(9_000)
    }

    private fun service(config: MicrosubConfig = MicrosubConfig()) = StorageQueryService(channels, stats, config, clock)

    @Test
    fun `a replica reuses the report for the cache period and then recomputes it`() {
        val service = service()
        val first = service.report()
        clock.now = clock.now.plus(Duration.ofMinutes(4))
        assertSame(first, service.report())
        verify(stats, times(1)).storageByFeed()

        clock.now = clock.now.plus(Duration.ofMinutes(2))
        val second = service.report()
        verify(stats, times(2)).storageByFeed()
        assertEquals(clock.now, second.computedAt)
        assertEquals(Duration.ofMinutes(5), second.ttl)
    }

    @Test
    fun `entries without a followed feed count towards their channel and the total`() {
        val report = service().report()
        assertEquals(StorageUsage(10, 4, 2, 1000), report.channels.single().usage)
        assertEquals(StorageUsage(10, 4, 2, 1000), report.total)
        assertEquals(5_000, report.onDiskBytes)
        assertEquals(9_000L, report.databaseBytes)
    }

    @Test
    fun `warnings are off by default and only inform once a limit is exceeded`() {
        assertEquals(emptyList<StorageWarning>(), service().report().warnings)

        val warned = service(MicrosubConfig(storageWarnBytes = 4_999, storageWarnEntries = 9)).report()
        assertEquals(listOf(StorageWarning("bytes_high", 4_999, 5_000), StorageWarning("entries_high", 9, 10)), warned.warnings)

        val atLimit = service(MicrosubConfig(storageWarnBytes = 5_000, storageWarnEntries = 10)).report()
        assertEquals(emptyList<StorageWarning>(), atLimit.warnings, "only exceeding a limit warns")
    }

    @Test
    fun `an unreadable database size does not fail the report`() {
        whenever(stats.databaseBytes()).thenThrow(IllegalStateException("denied"))
        val report = service().report()
        assertNull(report.databaseBytes)
        assertEquals(5_000, report.onDiskBytes)
    }
}
