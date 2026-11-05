package dev.jacobandersen.bastion.websub.internal

import dev.jacobandersen.bastion.post.events.PostChangeType
import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.post.events.PostFeedFacets
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WebsubTopicResolverTest {
    private val topics =
        WebsubConfig.Topics(
            main = "https://x.test/",
            uncategorized = "https://x.test/uncategorized",
            type = "https://x.test/{type}",
            typeNames = listOf("note", "article"),
            tag = "https://x.test/tags/{tag}",
            year = "https://x.test/{year}",
        )

    private fun resolver(topics: WebsubConfig.Topics = this.topics) = WebsubTopicResolver(WebsubConfig(topics = topics))

    private fun facets(
        type: String? = "note",
        tags: List<String> = emptyList(),
        year: Int? = 2026,
    ) = PostFeedFacets(type, tags, year)

    private fun event(
        previous: PostFeedFacets? = null,
        current: PostFeedFacets? = facets(),
        wasVisible: Boolean = previous != null,
        isVisible: Boolean = true,
    ) = PostChangedEvent(
        postId = UUID.randomUUID(),
        changeType = PostChangeType.UPDATED,
        wasFeedVisible = wasVisible,
        isFeedVisible = isVisible,
        occurredAt = Instant.now(),
        previousFacets = previous,
        facets = current,
    )

    @Test
    fun `untagged post pings main, uncategorized, type and year feeds`() {
        assertEquals(
            setOf("https://x.test/", "https://x.test/uncategorized", "https://x.test/note", "https://x.test/2026"),
            resolver().resolve(event(), 2026),
        )
    }

    @Test
    fun `tagged post pings a feed per tag and skips uncategorized`() {
        val result = resolver().resolve(event(current = facets(tags = listOf("kotlin", "web dev"))), 2026)

        assertEquals(true, "https://x.test/tags/kotlin" in result)
        assertEquals(true, "https://x.test/tags/web%20dev" in result)
        assertEquals(false, "https://x.test/uncategorized" in result)
    }

    @Test
    fun `tags are encoded like encodeURIComponent`() {
        val result = resolver().resolve(event(current = facets(tags = listOf("c++/ü (it's!)"))), 2026)

        assertEquals(true, "https://x.test/tags/c%2B%2B%2F%C3%BC%20(it's!)" in result)
    }

    @Test
    fun `types without a feed are skipped`() {
        val result = resolver().resolve(event(current = facets(type = "event")), 2026)

        assertEquals(false, result.any { it.endsWith("/event") })
    }

    @Test
    fun `empty type names allow every type`() {
        val result = resolver(topics.copy(typeNames = emptyList())).resolve(event(current = facets(type = "event")), 2026)

        assertEquals(true, "https://x.test/event" in result)
    }

    @Test
    fun `past year feeds are archives and not pinged`() {
        val result = resolver().resolve(event(current = facets(year = 2025)), 2026)

        assertEquals(false, "https://x.test/2025" in result)
        assertEquals(false, "https://x.test/2026" in result)
    }

    @Test
    fun `feeds the post left are pinged too`() {
        val result =
            resolver().resolve(
                event(
                    previous = facets(type = "note", tags = listOf("old")),
                    current = facets(type = "article", tags = listOf("new")),
                ),
                2026,
            )

        assertEquals(true, "https://x.test/tags/old" in result)
        assertEquals(true, "https://x.test/tags/new" in result)
        assertEquals(true, "https://x.test/note" in result)
        assertEquals(true, "https://x.test/article" in result)
    }

    @Test
    fun `a demoted post only pings its previous feeds`() {
        val result =
            resolver().resolve(
                event(previous = facets(tags = listOf("old")), current = facets(tags = listOf("new")), isVisible = false),
                2026,
            )

        assertEquals(true, "https://x.test/tags/old" in result)
        assertEquals(false, "https://x.test/tags/new" in result)
    }

    @Test
    fun `nothing is resolved when no topics are configured`() {
        assertEquals(emptySet<String>(), resolver(WebsubConfig.Topics()).resolve(event(), 2026))
    }

    @Test
    fun `templates must contain their placeholder`() {
        assertThrows(IllegalArgumentException::class.java) { WebsubConfig.Topics(tag = "https://x.test/tags") }
    }
}
