package dev.jacobandersen.bastion.post.internal.store

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.post.events.PostChangeType
import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
@RecordApplicationEvents
class PostChangedEventTest {
    @Autowired
    lateinit var postService: PostService

    @Autowired
    lateinit var events: ApplicationEvents

    private fun uniqueSlug(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun post() =
        Mf2Object(
            type = listOf("h-entry"),
            properties = mutableMapOf("content" to listOf(Mf2Value.String("Body"))),
            children = null,
        )

    private fun created(
        status: PostStatus = PostStatus.PUBLISHED,
        visibility: PostVisibility = PostVisibility.PUBLIC,
    ): Post = postService.create(uniqueSlug("event"), status, visibility, false, post())

    private fun lastEvent(): PostChangedEvent = events.stream(PostChangedEvent::class.java).toList().last()

    @Test
    fun `create publishes a created event with feed visibility`() {
        created()

        val event = lastEvent()
        assertEquals(PostChangeType.CREATED, event.changeType)
        assertFalse(event.wasFeedVisible)
        assertTrue(event.isFeedVisible)
    }

    @Test
    fun `creating a draft does not mark it feed-visible`() {
        created(status = PostStatus.DRAFT)

        val event = lastEvent()
        assertEquals(PostChangeType.CREATED, event.changeType)
        assertFalse(event.isFeedVisible)
    }

    @Test
    fun `update publishes an updated event`() {
        val post = created()
        events.clear()

        postService.updatePost(post.copy(post = post.post.setProperty("name", Mf2Value.String("Renamed"))), post.post)

        val event = lastEvent()
        assertEquals(PostChangeType.UPDATED, event.changeType)
        assertTrue(event.wasFeedVisible)
        assertTrue(event.isFeedVisible)
    }

    @Test
    fun `demoting a post to private publishes an updated event that leaves the feed`() {
        val post = created()
        events.clear()

        postService.updatePost(post.copy(visibility = PostVisibility.PRIVATE), post.post)

        val event = lastEvent()
        assertEquals(PostChangeType.UPDATED, event.changeType)
        assertTrue(event.wasFeedVisible)
        assertFalse(event.isFeedVisible)
    }

    @Test
    fun `delete publishes a deleted event`() {
        val post = created()
        events.clear()

        postService.updatePost(post.copy(deleted = true), post.post)

        val event = lastEvent()
        assertEquals(PostChangeType.DELETED, event.changeType)
        assertTrue(event.wasFeedVisible)
        assertFalse(event.isFeedVisible)
    }

    @Test
    fun `undelete publishes a restored event`() {
        val post = created()
        val deleted = postService.updatePost(post.copy(deleted = true), post.post)
        events.clear()

        postService.updatePost(deleted.copy(deleted = false), deleted.post)

        val event = lastEvent()
        assertEquals(PostChangeType.RESTORED, event.changeType)
        assertFalse(event.wasFeedVisible)
        assertTrue(event.isFeedVisible)
    }

    @Test
    fun `unlisted posts are reachable but not feed-visible`() {
        created(visibility = PostVisibility.UNLISTED)

        val event = lastEvent()
        assertFalse(event.isFeedVisible)
    }

    @Test
    fun `events carry the feed facets before and after an update`() {
        val tagged =
            postService.create(
                uniqueSlug("facets"),
                PostStatus.PUBLISHED,
                PostVisibility.PUBLIC,
                false,
                post().setProperty("category", Mf2Value.String("old")),
            )
        assertEquals(listOf("old"), lastEvent().facets?.tags)
        events.clear()

        postService.updatePost(tagged, tagged.post.setProperty("category", Mf2Value.String("new")))

        val event = lastEvent()
        assertEquals(listOf("old"), event.previousFacets?.tags)
        assertEquals(listOf("new"), event.facets?.tags)
    }
}
