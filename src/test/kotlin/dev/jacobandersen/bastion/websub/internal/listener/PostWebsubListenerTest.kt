package dev.jacobandersen.bastion.websub.internal.listener

import dev.jacobandersen.bastion.post.events.PostChangeType
import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.post.events.PostFeedFacets
import dev.jacobandersen.bastion.websub.internal.WebsubPublisher
import dev.jacobandersen.bastion.websub.internal.WebsubTopicResolver
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class PostWebsubListenerTest {
    private val publisher = mock<WebsubPublisher>()
    private val listener =
        PostWebsubListener(
            publisher,
            WebsubTopicResolver(WebsubConfig(topics = WebsubConfig.Topics(main = "https://example.com/feed"))),
        )

    private fun event(
        wasFeedVisible: Boolean,
        isFeedVisible: Boolean,
        changeType: PostChangeType = PostChangeType.UPDATED,
    ) = PostChangedEvent(
        postId = UUID.randomUUID(),
        changeType = changeType,
        wasFeedVisible = wasFeedVisible,
        isFeedVisible = isFeedVisible,
        occurredAt = Instant.now(),
        previousFacets = PostFeedFacets(null, emptyList(), null),
        facets = PostFeedFacets(null, emptyList(), null),
    )

    @Test
    fun `publishes when a post enters the feed`() {
        listener.on(event(wasFeedVisible = false, isFeedVisible = true, changeType = PostChangeType.CREATED))

        verify(publisher, times(1)).publish(setOf("https://example.com/feed"))
    }

    @Test
    fun `publishes when a feed-visible post is updated`() {
        listener.on(event(wasFeedVisible = true, isFeedVisible = true))

        verify(publisher, times(1)).publish(setOf("https://example.com/feed"))
    }

    @Test
    fun `publishes when a post leaves the feed`() {
        listener.on(event(wasFeedVisible = true, isFeedVisible = false))

        verify(publisher, times(1)).publish(setOf("https://example.com/feed"))
    }

    @Test
    fun `publishes when a post is deleted from the feed`() {
        listener.on(event(wasFeedVisible = true, isFeedVisible = false, changeType = PostChangeType.DELETED))

        verify(publisher, times(1)).publish(setOf("https://example.com/feed"))
    }

    @Test
    fun `publishes when a post is restored to the feed`() {
        listener.on(event(wasFeedVisible = false, isFeedVisible = true, changeType = PostChangeType.RESTORED))

        verify(publisher, times(1)).publish(setOf("https://example.com/feed"))
    }

    @Test
    fun `does not publish for changes that never touch the feed`() {
        listener.on(event(wasFeedVisible = false, isFeedVisible = false))

        verify(publisher, never()).publish(any())
    }

    @Test
    fun `does not publish for a draft deletion`() {
        listener.on(event(wasFeedVisible = false, isFeedVisible = false, changeType = PostChangeType.DELETED))

        verify(publisher, never()).publish(any())
    }

    @Test
    fun `publish failures propagate to the event registry`() {
        whenever(publisher.publish(any())).thenThrow(RuntimeException("hub unavailable"))

        assertThrows(RuntimeException::class.java) {
            listener.on(event(wasFeedVisible = false, isFeedVisible = true))
        }
    }
}
