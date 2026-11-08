package dev.jacobandersen.bastion.microsub.internal.listener

import dev.jacobandersen.bastion.microsub.internal.feed.Feed
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRepository
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedFormat
import dev.jacobandersen.bastion.websub.events.WebsubContentNotifiedEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class WebsubContentListenerTest {
    private val feeds = mock<FeedRepository>()
    private val refresh = mock<FeedRefreshService>()
    private val listener = WebsubContentListener(feeds, refresh)

    @Test
    fun `requests a refresh of every feed on the notified subscription`() {
        val subscription = UUID.randomUUID()
        val a = feed(subscription)
        val b = feed(subscription)
        whenever(feeds.listBySubscription(subscription)).thenReturn(listOf(a, b))

        listener.on(WebsubContentNotifiedEvent(UUID.randomUUID(), subscription, "https://x.example/feed", Instant.now()))

        verify(refresh).requestRefresh(a.id)
        verify(refresh).requestRefresh(b.id)
    }

    private fun feed(subscription: UUID) =
        Feed(
            id = UUID.randomUUID(),
            channelId = UUID.randomUUID(),
            url = "https://x.example/",
            fetchUrl = "https://x.example/feed",
            format = FeedFormat.RSS,
            name = null,
            photo = null,
            description = null,
            hub = "https://hub.example/",
            selfUrl = "https://x.example/feed",
            subscriptionId = subscription,
            etag = null,
            lastModified = null,
            failureCount = 0,
        )
}
