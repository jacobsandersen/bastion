package dev.jacobandersen.bastion.websub.events

import java.time.Instant
import java.util.UUID

/**
 * A hub delivered new content for a subscribed topic, with a valid signature.
 * The body is deliberately not carried: listeners re-fetch [topic] so
 * duplicate or out-of-order deliveries converge on the topic's current state.
 * [eventId] is unique per delivery, for deduplication.
 */
data class WebsubContentNotifiedEvent(
    val eventId: UUID,
    val subscriptionId: UUID,
    val topic: String,
    val receivedAt: Instant,
)
