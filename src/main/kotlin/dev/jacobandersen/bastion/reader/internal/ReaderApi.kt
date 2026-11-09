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

    /** The only scope the API checks. Microsub owns the bare `read` scope; the reader API does not define one. */
    const val REQUIRED_SCOPE = "read"

    const val FEATURE_REFRESH = "refresh"
    const val FEATURE_EVENTS = "events"
    const val FEATURE_FEED_DETAILS = "feed-details"

    /** New features are additive; [VERSION] changes only for breaking changes. */
    val FEATURES = listOf(FEATURE_REFRESH, FEATURE_EVENTS, FEATURE_FEED_DETAILS)
}
