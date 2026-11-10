package dev.jacobandersen.bastion.reader.internal

/** The reader API's identity: one place for the path, name, version and feature flags the capability document lists. */
object ReaderApi {
    const val PATH = "/reader/v1"
    const val NAME = "bastion-reader"
    const val VERSION = 1

    /** The Microsub endpoint this API sits next to; clients cross-check it against the Microsub endpoint they use. */
    const val MICROSUB_PATH = "/microsub"

    /** The metadata member advertising the API in the IndieAuth server metadata. */
    const val METADATA_MEMBER = "bastion_reader_api"

    /** The scope every route but the capability document needs. Microsub owns the bare `read` scope; the reader API defines none. */
    const val REQUIRED_SCOPE = "read"

    /** Bulk delete also needs Microsub's `channels` scope, which already covers deleting a whole channel. */
    const val BULK_DELETE_SCOPE = "channels"

    const val FEATURE_REFRESH = "refresh"
    const val FEATURE_EVENTS = "events"
    const val FEATURE_FEED_DETAILS = "feed-details"
    const val FEATURE_ENTRY_CHECK = "entry-check"
    const val FEATURE_DELETED_ENTRIES = "deleted-entries"
    const val FEATURE_BULK_DELETE = "bulk-delete"
    const val FEATURE_UNREAD_TOTALS = "unread-totals"
    const val FEATURE_STORAGE = "storage"
    const val FEATURE_SAVED = "saved"

    /** Saving folder structure changes (create, rename, delete a folder) also needs Microsub's `channels` scope. */
    const val SAVED_FOLDER_SCOPE = "channels"

    /** New features are additive; [VERSION] changes only for breaking changes. */
    val FEATURES =
        listOf(
            FEATURE_REFRESH,
            FEATURE_EVENTS,
            FEATURE_FEED_DETAILS,
            FEATURE_ENTRY_CHECK,
            FEATURE_DELETED_ENTRIES,
            FEATURE_BULK_DELETE,
            FEATURE_UNREAD_TOTALS,
            FEATURE_STORAGE,
            FEATURE_SAVED,
        )
}
