package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WebmentionThreadResolverTest {
    private val postId = UUID.randomUUID()
    private val alice = "https://alice.example/2026/01/01/post"
    private val bob = "https://bob.example/comments/1"
    private val carol = "https://carol.example/replies/1"

    private fun mention(
        sourceUrl: String,
        inReplyTo: List<String>?,
        seenAt: Long,
        url: String? = null,
        viaId: UUID? = null,
        rawMf2: Mf2Object? = null,
    ) = ReceivedWebmention(
        id = UUID.randomUUID(),
        postId = postId,
        sourceUrl = sourceUrl,
        targetUrl = alice,
        state = ReceivedWebmentionState.VERIFIED,
        interaction = WebmentionInteraction.REPLY,
        authorName = null,
        authorUrl = null,
        authorPhoto = null,
        contentText = emptyList(),
        contentHtml = emptyList(),
        rawMf2 = rawMf2,
        url = url,
        inReplyTo = inReplyTo,
        viaId = viaId,
        displayFingerprint = null,
        lastError = null,
        firstSeenAt = Instant.ofEpochSecond(seenAt),
        verifiedAt = Instant.ofEpochSecond(seenAt),
        updatedAtUtc = Instant.ofEpochSecond(seenAt),
    )

    private fun resolve(mentions: List<ReceivedWebmention>) =
        WebmentionThreadResolver.resolve(mentions) { it.startsWith("https://alice.example/") }

    private fun List<ThreadedWebmention>.parentOf(sourceUrl: String) = single { it.mention.sourceUrl == sourceUrl }.parent?.sourceUrl

    @Test
    fun `threads a direct reply to a reply under its parent`() {
        val threaded =
            resolve(
                listOf(
                    mention(bob, listOf(alice), seenAt = 1),
                    mention(carol, listOf("$bob#comment", alice), seenAt = 2),
                ),
            )

        assertNull(threaded.parentOf(bob))
        assertEquals(bob, threaded.parentOf(carol))
    }

    @Test
    fun `threads replies that arrived before their parent`() {
        val threaded =
            resolve(
                listOf(
                    mention(carol, listOf(bob), seenAt = 1),
                    mention(bob, listOf(alice), seenAt = 2),
                ),
            )

        assertEquals(listOf(carol, bob), threaded.map { it.mention.sourceUrl })
        assertEquals(bob, threaded.parentOf(carol))
    }

    @Test
    fun `matches a parent by its canonical url`() {
        val threaded =
            resolve(
                listOf(
                    mention("https://bob.example/?p=1", listOf(alice), seenAt = 1, url = "$bob/"),
                    mention(carol, listOf(bob), seenAt = 2),
                ),
            )

        assertEquals("https://bob.example/?p=1", threaded.parentOf(carol))
    }

    @Test
    fun `falls back to the displaying source for a nested response with no known parent`() {
        val via = mention(bob, listOf(alice), seenAt = 1)
        val threaded =
            resolve(
                listOf(via, mention(carol, emptyList(), seenAt = 2, viaId = via.id)),
            )

        assertEquals(bob, threaded.parentOf(carol))
        assertEquals(bob, threaded.single { it.mention.sourceUrl == carol }.via?.sourceUrl)
    }

    @Test
    fun `hides nested responses whose displaying source is not shown`() {
        val threaded =
            resolve(listOf(mention(carol, listOf(bob), seenAt = 2, viaId = UUID.randomUUID())))

        assertEquals(emptyList<ThreadedWebmention>(), threaded)
    }

    @Test
    fun `breaks reply cycles at the oldest response`() {
        val threaded =
            resolve(
                listOf(
                    mention(bob, listOf(carol), seenAt = 1),
                    mention(carol, listOf(bob), seenAt = 2),
                ),
            )

        assertNull(threaded.parentOf(bob))
        assertEquals(bob, threaded.parentOf(carol))
    }

    @Test
    fun `derives threading from stored microformats for rows recorded before threading existed`() {
        val raw =
            Mf2Object(
                type = listOf("h-entry"),
                properties = mapOf("in-reply-to" to listOf(Mf2Value.String(bob))),
            )
        val threaded =
            resolve(
                listOf(
                    mention(bob, null, seenAt = 1),
                    mention(carol, null, seenAt = 2, rawMf2 = raw),
                ),
            )

        assertEquals(bob, threaded.parentOf(carol))
        assertEquals(listOf(bob), threaded.single { it.mention.sourceUrl == carol }.inReplyTo)
    }

    @Test
    fun `keeps the parent of a response that only leads into a cycle`() {
        val dave = "https://dave.example/r/1"
        val threaded =
            resolve(
                listOf(
                    mention(bob, listOf(carol), seenAt = 1),
                    mention(carol, listOf(dave), seenAt = 2),
                    mention(dave, listOf(carol), seenAt = 3),
                ),
            )

        assertEquals(carol, threaded.parentOf(bob))
        assertNull(threaded.parentOf(carol))
        assertEquals(carol, threaded.parentOf(dave))
    }

    @Test
    fun `threads a reply to a fragment comment under that comment, else under its page`() {
        val bobPage = "https://bob.example/post/"
        val bobEntry = mention(bobPage, listOf(alice), seenAt = 1)
        val threaded =
            resolve(
                listOf(
                    bobEntry,
                    mention("$bobPage#comment-12", listOf(bobPage), seenAt = 2, viaId = bobEntry.id),
                    mention(carol, listOf("https://bob.example/post#comment-12"), seenAt = 3),
                    mention("https://dave.example/r/1", listOf("$bobPage#comment-99"), seenAt = 4),
                ),
            )

        assertEquals("$bobPage#comment-12", threaded.parentOf(carol))
        assertEquals(bobPage, threaded.parentOf("https://dave.example/r/1"))
        assertEquals(bobPage, threaded.parentOf("$bobPage#comment-12"))
    }

    @Test
    fun `an unknown fragment falls back to its page, not to a sibling fragment comment`() {
        val eve = mention("https://eve.example/p/1", listOf(alice), seenAt = 1)
        val comment1 = "https://bob.example/post#comment-1"
        val dave = "https://dave.example/r/1"
        val threaded =
            resolve(
                listOf(
                    eve,
                    mention(comment1, listOf("https://bob.example/elsewhere"), seenAt = 2, viaId = eve.id),
                    mention("https://bob.example/post", listOf(alice), seenAt = 3),
                    mention(dave, listOf("https://bob.example/post#comment-99"), seenAt = 4),
                ),
            )

        assertEquals("https://bob.example/post", threaded.parentOf(dave))
    }

    @Test
    fun `a cross-origin u-url does not capture replies to another site's response`() {
        val bobEntry = mention(bob, listOf(alice), seenAt = 1)
        val dave = "https://dave.example/r/1"
        val threaded =
            resolve(
                listOf(
                    mention("https://mallory.example/fake", listOf(alice), seenAt = 0, url = carol),
                    bobEntry,
                    mention(carol, null, seenAt = 2, url = carol, viaId = bobEntry.id),
                    mention(dave, listOf(carol), seenAt = 3),
                ),
            )

        assertEquals(carol, threaded.parentOf(dave))
    }

    @Test
    fun `threads a Bridgy reply chain through cross-origin u-urls`() {
        val bobMastodon = "https://mastodon.social/@bob/1"
        val bobBridgy = "https://brid.gy/comment/mastodon/alice/1/bob"
        val carolBridgy = "https://brid.gy/comment/mastodon/alice/1/carol"
        val threaded =
            resolve(
                listOf(
                    mention(bobBridgy, listOf(alice), seenAt = 1, url = bobMastodon),
                    mention(carolBridgy, listOf(alice, bobMastodon), seenAt = 2, url = "https://mastodon.social/@carol/2"),
                ),
            )

        assertEquals(bobBridgy, threaded.parentOf(carolBridgy))
    }

    @Test
    fun `an older cross-origin u-url does not displace the response that owns the url`() {
        val dave = "https://dave.example/r/1"
        val threaded =
            resolve(
                listOf(
                    mention("https://mallory.example/fake", listOf(alice), seenAt = 0, url = "$carol/"),
                    mention(carol, listOf(alice), seenAt = 5),
                    mention(dave, listOf("$carol#reply"), seenAt = 6),
                ),
            )

        assertEquals(carol, threaded.parentOf(dave))
    }

    @Test
    fun `a cross-origin u-url naming the post itself captures no replies`() {
        val threaded =
            resolve(
                listOf(
                    mention("https://mallory.example/x", listOf(alice), seenAt = 0, url = alice),
                    mention(bob, listOf(alice), seenAt = 1),
                    mention(carol, listOf(alice, bob), seenAt = 2),
                ),
            )

        assertNull(threaded.parentOf(bob))
        assertEquals(bob, threaded.parentOf(carol))
    }
}
