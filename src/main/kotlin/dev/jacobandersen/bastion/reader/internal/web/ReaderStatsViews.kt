package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.microsub.stats.StorageReport
import dev.jacobandersen.bastion.microsub.stats.StorageUsage
import dev.jacobandersen.bastion.microsub.stats.UnreadTotals
import java.time.format.DateTimeFormatter

internal fun UnreadTotals.toBody(): Map<String, Any> =
    mapOf(
        "total" to total,
        "channels" to channels.map { mapOf("uid" to it.uid, "unread" to it.unread) },
        "feeds" to feeds.map { mapOf("channel" to it.channel, "url" to it.url, "unread" to it.unread) },
    )

private fun StorageUsage.toBody(): Map<String, Long> =
    mapOf(
        "entries" to entries,
        "unread" to unread,
        "removed" to removed,
        "bytes" to bytes,
    )

internal fun StorageReport.toBody(): Map<String, Any?> =
    mapOf(
        "computed_at" to DateTimeFormatter.ISO_INSTANT.format(computedAt),
        "ttl_seconds" to ttl.seconds,
        "database_bytes" to databaseBytes,
        "microsub" to
            mapOf(
                "on_disk_bytes" to onDiskBytes,
                "payload_bytes" to total.bytes,
                "entries" to total.entries,
                "unread" to total.unread,
                "removed" to total.removed,
                "channels" to channels.map { mapOf("uid" to it.uid) + it.usage.toBody() },
                "feeds" to feeds.map { mapOf("channel" to it.channel, "url" to it.url) + it.usage.toBody() },
            ),
        "warnings" to warnings.map { mapOf("code" to it.code, "threshold" to it.threshold, "value" to it.value) },
    )
