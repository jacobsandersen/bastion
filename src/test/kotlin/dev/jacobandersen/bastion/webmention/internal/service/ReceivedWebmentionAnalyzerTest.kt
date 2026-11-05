package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.microformats2.Mf2ParserImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceivedWebmentionAnalyzerTest {
    private fun analyze(
        html: String,
        baseUrl: String = "https://source.example/post/1",
    ) = ReceivedWebmentionAnalyzer.analyze(Mf2ParserImpl().parse(html, baseUrl))

    @Test
    fun `classifies an in-reply-to as a reply and extracts author`() {
        val analysis =
            analyze(
                """
                <article class="h-entry">
                  <a class="p-author h-card" href="https://alice.example/">
                    <img class="u-photo" alt="" src="https://alice.example/me.jpg"/>Alice
                  </a>
                  <div class="e-content">Great point!</div>
                  <a class="u-in-reply-to" href="https://blog.example/2026/01/01/post">the post</a>
                </article>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.REPLY, analysis.interaction)
        assertEquals("Alice", analysis.authorName)
        assertEquals("https://alice.example/", analysis.authorUrl)
        assertEquals(listOf("Great point!"), analysis.contentText)
    }

    @Test
    fun `classifies a nested h-cite like-of as a like`() {
        val analysis =
            analyze(
                """
                <div class="h-entry">
                  <p class="e-content">liked</p>
                  <a class="u-like-of h-cite" href="https://blog.example/2026/01/01/post">
                    <span class="p-name">A post</span>
                  </a>
                </div>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.LIKE, analysis.interaction)
    }

    @Test
    fun `classifies an rsvp even when it also replies`() {
        val analysis =
            analyze(
                """
                <div class="h-entry">
                  <a class="u-in-reply-to" href="https://events.example/2026/01/01/party">the party</a>
                  <span class="p-rsvp">yes</span>
                </div>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.RSVP, analysis.interaction)
    }

    @Test
    fun `classifies repost and bookmark properties`() {
        val repost =
            analyze(
                """
                <div class="h-entry">
                  <a class="u-repost-of" href="https://blog.example/2026/01/01/post">repost</a>
                </div>
                """.trimIndent(),
            )
        assertEquals(WebmentionInteraction.REPOST, repost.interaction)

        val bookmark =
            analyze(
                """
                <div class="h-entry">
                  <a class="u-bookmark-of" href="https://blog.example/2026/01/01/post">bookmark</a>
                </div>
                """.trimIndent(),
            )
        assertEquals(WebmentionInteraction.BOOKMARK, bookmark.interaction)
    }

    @Test
    fun `falls back to rel attributes when no h-entry property declares an interaction`() {
        val analysis =
            analyze(
                """
                <div class="h-entry">
                  <p class="e-content">This is my comment on something.</p>
                </div>
                <a rel="in-reply-to" href="https://blog.example/2026/01/01/post">context</a>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.REPLY, analysis.interaction)
    }

    @Test
    fun `a plain mention has no declaring interaction property`() {
        val analysis =
            analyze(
                """
                <div class="h-entry">
                  <div class="e-content">Here is <a href="https://blog.example/2026/01/01/post">a post</a>.</div>
                </div>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.MENTION, analysis.interaction)
        assertEquals(listOf("Here is a post."), analysis.contentText)
    }

    @Test
    fun `extracts content html when present`() {
        val analysis =
            analyze(
                """
                <div class="h-entry">
                  <div class="e-content"><b>Hello</b> world</div>
                </div>
                """.trimIndent(),
            )

        assertEquals(WebmentionInteraction.MENTION, analysis.interaction)
        assertEquals(listOf("<b>Hello</b> world"), analysis.contentHtml)
        assertEquals(listOf("Hello world"), analysis.contentText)
    }

    @Test
    fun `no microformats at all is still a mention`() {
        val analysis =
            analyze("""<html><body><p>just prose <a href="https://blog.example/1">link</a></p></body></html>""")
        assertEquals(WebmentionInteraction.MENTION, analysis.interaction)
        assertEquals(null, analysis.primary)
    }

    @Test
    fun `extracts the canonical url and in-reply-to from plain and h-cite values`() {
        val analysis =
            analyze(
                """
                <article class="h-entry">
                  <a class="u-url" href="https://carol.example/replies/1">permalink</a>
                  <a class="u-in-reply-to" href="https://bob.example/comments/1#c">Bob</a>
                  <div class="u-in-reply-to h-cite">
                    <a class="u-url p-name" href="https://blog.example/2026/01/01/post">Alice's post</a>
                  </div>
                  <p class="e-content">Replying to Bob</p>
                </article>
                """.trimIndent(),
                baseUrl = "https://carol.example/replies/1",
            )

        assertEquals(WebmentionInteraction.REPLY, analysis.interaction)
        assertEquals("https://carol.example/replies/1", analysis.url)
        assertEquals(
            listOf("https://bob.example/comments/1#c", "https://blog.example/2026/01/01/post"),
            analysis.inReplyTo,
        )
    }

    @Test
    fun `collects nested comments recursively and threads them to their container`() {
        val analysis =
            ReceivedWebmentionAnalyzer.analyze(
                Mf2ParserImpl().parse(
                    """
                    <article class="h-entry">
                      <a class="u-url" href="https://bob.example/comments/1">permalink</a>
                      <a class="u-in-reply-to" href="https://blog.example/2026/01/01/post">Alice</a>
                      <p class="e-content">Bob's comment</p>
                      <div class="p-comment h-cite">
                        <a class="p-author h-card" href="https://carol.example/">Carol</a>
                        <a class="u-url" href="https://carol.example/replies/1">permalink</a>
                        <p class="p-content">Carol's reply</p>
                        <div class="p-comment h-cite">
                          <a class="u-url" href="https://dave.example/r/1">permalink</a>
                          <a class="u-like-of" href="https://carol.example/replies/1">liked</a>
                        </div>
                      </div>
                      <div class="h-entry">
                        <a class="u-url" href="https://erin.example/n/1">permalink</a>
                        <a class="u-in-reply-to" href="https://carol.example/replies/1">Carol</a>
                        <p class="e-content">Erin agrees</p>
                      </div>
                      <div class="p-comment h-cite"><p class="p-content">no url, skipped</p></div>
                    </article>
                    """.trimIndent(),
                    "https://bob.example/comments/1",
                ),
                sourceUrl = "https://bob.example/comments/1",
            )

        assertEquals(
            listOf("https://carol.example/replies/1", "https://dave.example/r/1", "https://erin.example/n/1"),
            analysis.nested.map { it.url },
        )
        val carol = analysis.nested[0].analysis
        assertEquals(WebmentionInteraction.REPLY, carol.interaction)
        assertEquals("Carol", carol.authorName)
        assertEquals(listOf("https://bob.example/comments/1"), carol.inReplyTo)
        assertEquals(listOf("Carol's reply"), carol.contentText)
        val dave = analysis.nested[1].analysis
        assertEquals(WebmentionInteraction.LIKE, dave.interaction)
        assertEquals(listOf("https://carol.example/replies/1"), dave.inReplyTo)
        assertEquals(listOf("https://carol.example/replies/1"), analysis.nested[2].analysis.inReplyTo)
        assertTrue(analysis.nested.all { it.analysis.nested.isEmpty() })
    }

    @Test
    fun `limits nested comment depth and count`() {
        fun nest(depth: Int): String =
            if (depth == 0) {
                ""
            } else {
                """<div class="p-comment h-cite"><a class="u-url" href="https://deep.example/$depth">x</a>${nest(depth - 1)}</div>"""
            }
        val deep = analyze("""<article class="h-entry"><a class="u-url" href="https://bob.example/1">p</a>${nest(8)}</article>""")
        assertEquals(ReceivedWebmentionAnalyzer.MAX_NESTED_DEPTH, deep.nested.size)

        val many =
            (1..80).joinToString("") {
                """<div class="p-comment h-cite"><a class="u-url" href="https://many.example/$it">x</a></div>"""
            }
        val wide = analyze("""<article class="h-entry"><a class="u-url" href="https://bob.example/1">p</a>$many</article>""")
        assertEquals(ReceivedWebmentionAnalyzer.MAX_NESTED_RESPONSES, wide.nested.size)
    }
}
