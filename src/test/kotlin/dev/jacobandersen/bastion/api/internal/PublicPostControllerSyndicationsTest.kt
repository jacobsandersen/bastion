package dev.jacobandersen.bastion.api.internal

import dev.jacobandersen.bastion.api.internal.post.PublicPostController
import dev.jacobandersen.bastion.api.internal.post.dto.PostResponse
import dev.jacobandersen.bastion.post.read.PostFeedPage
import dev.jacobandersen.bastion.post.read.PostFeedQuery
import dev.jacobandersen.bastion.post.read.PostLookup
import dev.jacobandersen.bastion.post.read.PostReadService
import dev.jacobandersen.bastion.post.read.PostView
import dev.jacobandersen.bastion.post.read.SyndicationLinkView
import dev.jacobandersen.bastion.webmention.read.PostWebmentionsView
import dev.jacobandersen.bastion.webmention.read.WebmentionCountsView
import dev.jacobandersen.bastion.webmention.read.WebmentionReadService
import dev.jacobandersen.bastion.webmention.read.WebmentionView
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

class PublicPostControllerSyndicationsTest {
    private val postId = UUID.randomUUID()
    private val post =
        PostView(
            id = postId,
            slug = "slug",
            url = "https://bastion.test/2026/01/01/slug",
            h = "h-entry",
            type = null,
            published = "2026-01-01T00:00:00Z",
            updated = "2026-01-02T00:00:00Z",
            name = null,
            summary = emptyList(),
            content = emptyList(),
            contentHtml = emptyList(),
            category = emptyList(),
            properties =
                mapOf(
                    "published" to listOf(Mf2Value.String("2026-01-01T00:00:00Z")),
                    "updated" to listOf(Mf2Value.String("2026-01-02T00:00:00Z")),
                ),
        )

    private fun controller(links: List<SyndicationLinkView>): PublicPostController {
        val postReadService: PostReadService = mock()
        val webmentionReadService: WebmentionReadService = mock()
        whenever(postReadService.lookupBySlug("slug")).thenReturn(PostLookup.Found(post))
        whenever(webmentionReadService.countsForPost(postId)).thenReturn(WebmentionCountsView.EMPTY)
        whenever(webmentionReadService.verifiedByPost(postId)).thenReturn(emptyList())
        whenever(postReadService.syndicationLinks(postId)).thenReturn(links)
        return PublicPostController(postReadService, webmentionReadService)
    }

    private fun body(controller: PublicPostController): PostResponse {
        val response = controller.bySlug("slug")
        assertEquals(HttpStatus.OK, response.statusCode)
        return response.body as PostResponse
    }

    @Test
    fun `single lookup includes uid name and url for syndicated copies`() {
        val c =
            controller(
                links =
                    listOf(
                        SyndicationLinkView(
                            uid = "bridgy",
                            name = "Bridgy",
                            url = "https://brid.gy/copy/1",
                        ),
                    ),
            )
        val syndications = body(c).syndications
        assertEquals(1, syndications?.size)
        assertEquals("bridgy", syndications?.first()?.uid)
        assertEquals("Bridgy", syndications?.first()?.name)
        assertEquals("https://brid.gy/copy/1", syndications?.first()?.url)
    }

    @Test
    fun `feed includes syndications and webmentions for each post`() {
        val postReadService: PostReadService = mock()
        val webmentionReadService: WebmentionReadService = mock()
        val counts = WebmentionCountsView(total = 1, reply = 0, like = 1, repost = 0, bookmark = 0, rsvp = 0, mention = 0)
        val like =
            WebmentionView(
                sourceUrl = "https://a.example/1",
                targetUrl = post.url,
                interaction = "LIKE",
                authorName = "Ann",
                authorUrl = "https://a.example",
                authorPhoto = null,
                contentText = emptyList(),
                contentHtml = emptyList(),
                firstSeenAt = Instant.parse("2026-01-03T00:00:00Z"),
                verifiedAt = Instant.parse("2026-01-03T00:00:00Z"),
                url = null,
                urlSameOrigin = false,
                inReplyTo = emptyList(),
                parentSourceUrl = null,
                viaSourceUrl = null,
            )
        whenever(postReadService.feed(any<PostFeedQuery>())).thenReturn(PostFeedPage(listOf(post), 1))
        whenever(postReadService.syndicationLinksByPostIds(any()))
            .thenReturn(mapOf(postId to listOf(SyndicationLinkView("bridgy", "Bridgy", "https://brid.gy/copy/1"))))
        whenever(webmentionReadService.byPostIds(any()))
            .thenReturn(mapOf(postId to PostWebmentionsView(counts, listOf(like))))
        val controller = PublicPostController(postReadService, webmentionReadService)

        val item = controller.feed(null, null, null, limit = null, offset = null, year = null, month = null, day = null).items.single()

        assertEquals("bridgy", item.syndications?.single()?.uid)
        assertEquals(1, item.webmentionCounts.like)
        assertEquals("Ann", item.webmentions?.single()?.authorName)
    }

    @Test
    fun `feed returns empty lists rather than null for posts with no webmentions or syndications`() {
        val postReadService: PostReadService = mock()
        val webmentionReadService: WebmentionReadService = mock()
        whenever(postReadService.feed(any<PostFeedQuery>())).thenReturn(PostFeedPage(listOf(post), 1))
        whenever(postReadService.syndicationLinksByPostIds(any())).thenReturn(emptyMap())
        whenever(webmentionReadService.byPostIds(any())).thenReturn(emptyMap())
        val controller = PublicPostController(postReadService, webmentionReadService)

        val item = controller.feed(null, null, null, limit = null, offset = null, year = null, month = null, day = null).items.single()

        assertEquals(emptyList<Any>(), item.syndications)
        assertEquals(emptyList<Any>(), item.webmentions)
    }
}
