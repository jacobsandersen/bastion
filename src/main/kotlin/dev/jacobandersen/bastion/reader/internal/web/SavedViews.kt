package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.reader.internal.saved.SavedFolderSummary
import dev.jacobandersen.bastion.reader.internal.saved.SavedItem
import dev.jacobandersen.bastion.reader.internal.saved.SavedStorage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun SavedFolderSummary.toBody(): Map<String, Any> =
    mapOf(
        "uid" to folder.uid,
        "name" to folder.name,
        "position" to folder.position,
        "count" to count,
        "bytes" to bytes,
        "version" to folder.version,
    )

/**
 * A saved item in the jf2 shape of a timeline entry (the stored copy, `_source` included), so a client renders it with
 * the same code, plus `_id` (the saved item's own id), `_saved_at`, `_folder` and `_origin`. There is no `_is_read`.
 */
internal fun SavedItem.toBody(): Map<String, Any?> =
    LinkedHashMap(data).apply {
        put("_id", id.toString())
        put("_saved_at", formatInstant(savedAt))
        put("_folder", folderUid)
        put(
            "_origin",
            buildMap {
                originChannelUid?.let { put("channel", it) }
                originEntryId?.let { put("entry", it.toString()) }
                originFeedUrl?.let { put("feed", it) }
            },
        )
    }

/** The sync manifest form of an item: enough to diff against a local copy. */
internal fun SavedItem.toSummaryBody(): Map<String, Any?> =
    buildMap {
        put("id", id.toString())
        put("entry", originEntryId?.toString())
        put("url", url)
        put("saved_at", formatInstant(savedAt))
    }

/** ISO-8601 with a `+00:00` offset, as Microsub formats the `published` of its items. */
private fun formatInstant(instant: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))

/** The `saved` member of `GET /reader/v1/storage`: separate from the `microsub` figures, which never include saved data. */
internal fun SavedStorage.toBody(): Map<String, Any> =
    mapOf(
        "items" to items,
        "folders" to folders.size,
        "on_disk_bytes" to onDiskBytes,
        "payload_bytes" to payloadBytes,
        "by_folder" to folders.map { mapOf("uid" to it.folder.uid, "name" to it.folder.name, "items" to it.count, "bytes" to it.bytes) },
    )
