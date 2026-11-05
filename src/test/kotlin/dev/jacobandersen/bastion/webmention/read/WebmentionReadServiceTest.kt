package dev.jacobandersen.bastion.webmention.read

import dev.jacobandersen.bastion.url.ContentUrlConfig
import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.service.ReceivedWebmentionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class WebmentionReadServiceTest {
    private val postId = UUID.randomUUID()
    private val received: ReceivedWebmentionService = mock()
    private val service =
        WebmentionReadService(
            received,
            ContentUrlMatcher(ContentUrlConfig(baseUrl = "https://alice.example", pathPattern = "{year}/{month}/{day}/{slug}")),
        )

    private fun mention(
        sourceUrl: String,
        url: String?,
    ) = ReceivedWebmention(
        id = UUID.randomUUID(),
        postId = postId,
        sourceUrl = sourceUrl,
        targetUrl = "https://alice.example/post",
        state = ReceivedWebmentionState.VERIFIED,
        interaction = WebmentionInteraction.REPLY,
        authorName = null,
        authorUrl = null,
        authorPhoto = null,
        contentText = emptyList(),
        contentHtml = emptyList(),
        rawMf2 = null,
        url = url,
        inReplyTo = emptyList(),
        viaId = null,
        displayFingerprint = null,
        lastError = null,
        firstSeenAt = Instant.EPOCH,
        verifiedAt = Instant.EPOCH,
        updatedAtUtc = Instant.EPOCH,
    )

    @Test
    fun `flags whether each response's url shares its source's origin`() {
        whenever(received.verifiedByPost(postId)).thenReturn(
            listOf(
                mention("https://bob.example/?p=1", "https://BOB.example:443/posts/1"),
                mention("https://mallory.example/fake", "https://carol.example/r"),
                mention("https://dave.example/r/1", null),
            ),
        )

        val views = service.verifiedByPost(postId).associate { it.sourceUrl to it.urlSameOrigin }

        assertEquals(
            mapOf(
                "https://bob.example/?p=1" to true,
                "https://mallory.example/fake" to false,
                "https://dave.example/r/1" to false,
            ),
            views,
        )
    }

    @Test
    fun `a response claiming the post's own url does not become the parent of other replies`() {
        val bob =
            mention("https://bob.example/r/1", null).copy(
                inReplyTo = listOf("https://alice.example/post"),
                firstSeenAt = Instant.ofEpochSecond(1),
            )
        whenever(received.verifiedByPost(postId)).thenReturn(
            listOf(mention("https://mallory.example/x", "http://alice.example/post/"), bob),
        )

        val parents = service.verifiedByPost(postId).associate { it.sourceUrl to it.parentSourceUrl }

        assertEquals(null, parents["https://bob.example/r/1"])
    }
}
