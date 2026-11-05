package dev.jacobandersen.bastion.webmention.events

import java.time.Instant
import java.util.UUID

/**
 * What a directly received webmention displays changed: it was verified for
 * the first time, its content changed, or it was deleted or rejected. Thin by
 * design; consumers load the latest state with
 * `WebmentionReadService.receivedById`, which reports whether the webmention
 * is displayed, gone, or still being verified. [eventId] is unique per change.
 */
data class ReceivedWebmentionChangedEvent(
    val eventId: UUID,
    val webmentionId: UUID,
    val postId: UUID,
    val occurredAt: Instant,
)
