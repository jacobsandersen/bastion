package dev.jacobandersen.bastion.websub.subscription

import java.time.Instant
import java.util.UUID

enum class WebsubSubscriptionState {
    /** A subscribe request was sent; the hub has not verified intent yet. */
    PENDING,

    /** The hub verified intent and delivers content until the lease expires. */
    ACTIVE,

    /** An unsubscribe request was sent; the hub has not verified it yet. */
    UNSUBSCRIBING,

    /** The hub refused the subscription. Callers fall back to polling. */
    DENIED,

    /** The hub rejected the subscribe request outright. Callers fall back to polling. */
    FAILED,
}

data class WebsubSubscriptionView(
    val id: UUID,
    val topic: String,
    val hub: String,
    val state: WebsubSubscriptionState,
    val leaseExpiresAt: Instant?,
    val lastError: String?,
)

/**
 * Bastion as a WebSub subscriber. A subscription is shared per (topic, hub):
 * callers that subscribe the same pair get the same id and must only
 * [unsubscribe] once nothing of theirs still uses it.
 *
 * Content deliveries are announced with
 * [dev.jacobandersen.bastion.websub.events.WebsubContentNotifiedEvent]; the
 * event does not carry the delivered body, so listeners re-fetch the topic.
 */
interface WebsubSubscriptionService {
    /** Subscribes to [topic] at [hub] (or re-subscribes an inactive subscription) and returns its id. */
    fun subscribe(
        topic: String,
        hub: String,
    ): UUID

    /** Asks the hub to stop delivering. Unknown ids are ignored. */
    fun unsubscribe(id: UUID)

    fun find(id: UUID): WebsubSubscriptionView?
}
