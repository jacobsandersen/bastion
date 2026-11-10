package dev.jacobandersen.bastion.reader.internal.web

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

/** The public capability document at `GET /reader/v1`. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReaderCapabilities(
    val api: String,
    val version: Int,
    val microsubEndpoint: String,
    val features: List<String>,
)

/** The body of `POST /reader/v1/channels/{uid}/entries/check`. */
data class EntryCheckRequest(
    val entry: List<String>? = null,
)

/**
 * The body of `POST /reader/v1/entries/delete`. [dryRun] defaults to true; an execution (`dry_run: false`) must quote
 * the dry run's `matched` count as [expected].
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class BulkDeleteRequest(
    val scope: BulkDeleteScopeRequest? = null,
    val dryRun: Boolean? = null,
    val expected: Int? = null,
    /** Required to execute a scope with `state: "all"`, which includes unread entries. */
    val confirmUnread: Boolean? = null,
)

/** Which entries to delete. All members are optional filters; they are ANDed. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class BulkDeleteScopeRequest(
    /** A channel uid, or `global` (the default) for every channel. */
    val channel: String? = null,
    /** A followed feed's url, fetch url or id. */
    val feed: String? = null,
    /** `read` (the default) or `all`, which also covers unread entries. */
    val state: String? = null,
    /** Only entries published before this ISO-8601 instant. */
    val publishedBefore: String? = null,
    /** Only entries left behind by a feed that was unfollowed. */
    val unfollowed: Boolean? = null,
)
