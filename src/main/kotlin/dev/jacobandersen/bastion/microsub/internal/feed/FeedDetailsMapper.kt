package dev.jacobandersen.bastion.microsub.internal.feed

import dev.jacobandersen.bastion.microsub.feeds.FeedDetails
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionService
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState
import org.springframework.stereotype.Component

/**
 * Derives the non-spec feed details (format, WebSub state). One implementation serves both the legacy `format` and
 * `websub` fields on Microsub feed objects and the `reader` API's feed details.
 */
@Component
class FeedDetailsMapper(
    private val websub: WebsubSubscriptionService,
) {
    fun of(feed: Feed): FeedDetails =
        FeedDetails(
            url = feed.url,
            name = feed.name,
            photo = feed.photo,
            description = feed.description,
            format = feed.format.name.lowercase(),
            websub = websubOf(feed),
        )

    /** `active` once the hub verified the subscription, `pending` while one is still settling, absent otherwise. */
    private fun websubOf(feed: Feed): String? =
        when (feed.subscriptionId?.let(websub::find)?.state) {
            WebsubSubscriptionState.ACTIVE -> "active"
            WebsubSubscriptionState.PENDING, WebsubSubscriptionState.UNSUBSCRIBING -> "pending"
            else -> null
        }
}
