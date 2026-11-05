package dev.jacobandersen.bastion.post.events

import java.time.Instant
import java.util.UUID

enum class PostChangeType {
    CREATED,
    UPDATED,
    DELETED,
    RESTORED,
}

/**
 * The properties of a post that decide which feeds it appears in: its
 * discovered post [type], its [tags] (the `category` values as written) and the
 * UTC year it was [publishedYear]. Carried on [PostChangedEvent] so consumers
 * can tell which feeds changed, including the feeds a post just left, without
 * loading the post.
 */
data class PostFeedFacets(
    val type: String?,
    val tags: List<String>,
    val publishedYear: Int?,
)

/**
 * A fact about a post write. The event is intentionally small and carries no
 * post body: consumers load the latest state and reconcile idempotently, so
 * out-of-order or duplicated delivery cannot resurrect stale state.
 *
 * [wasFeedVisible] and [isFeedVisible] describe the public feed transition,
 * which lets WebSub decide whether the topic changed without loading the post.
 * [previousFacets] and [facets] describe the feeds the post was in before and
 * after the write; [previousFacets] is null for a newly created post.
 */
data class PostChangedEvent(
    val postId: UUID,
    val changeType: PostChangeType,
    val wasFeedVisible: Boolean,
    val isFeedVisible: Boolean,
    val occurredAt: Instant,
    val previousFacets: PostFeedFacets? = null,
    val facets: PostFeedFacets? = null,
)
