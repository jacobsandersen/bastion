package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.microformats2.Mf2ParserImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mf2FeedParserTest {
    private val parser = Mf2ParserImpl()
    private val page = "https://alice.example/"

    private val html =
        """
        <html><head><title>Alice's site</title><link rel="hub" href="https://hub.example/"></head><body>
        <div class="h-card"><a class="u-url p-name" href="/">Alice</a><img class="u-photo" src="/me.jpg"></div>
        <div class="h-feed">
          <h1 class="p-name">Alice's notes</h1>
          <article class="h-entry">
            <div class="e-content p-name">Hello <b>world</b><script>alert(1)</script></div>
            <a class="u-url" href="/notes/1">#</a>
            <time class="dt-published" datetime="2026-11-01T10:00:00Z">Nov 1</time>
            <a class="p-category" href="#">indieweb</a>
          </article>
          <article class="h-entry">
            <h2 class="p-name">An article</h2>
            <div class="e-content"><p>Long body with <a href="/link">a link</a>.</p></div>
            <a class="u-url" href="/articles/2">#</a>
            <a class="u-like-of" href="https://bob.example/post">liked</a>
            <img class="u-photo" src="/photo.jpg">
          </article>
        </div></body></html>
        """.trimIndent()

    @Test
    fun `converts an h-feed to jf2 entries`() {
        val feed = Mf2FeedParser.parse(parser.parse(html, page), page, "Alice's site")

        assertEquals("Alice's notes", feed.name)
        assertEquals(listOf("https://hub.example/"), feed.hubs)
        assertEquals(2, feed.items.size)

        val note = feed.items[0]
        assertEquals("https://alice.example/notes/1", note.url)
        assertNull(note.data["name"], "an implied name duplicating the content is dropped")
        val content = note.data["content"] as Map<*, *>
        assertTrue((content["html"] as String).contains("<b>world</b>"))
        assertFalse((content["html"] as String).contains("script"))
        assertEquals(listOf("indieweb"), note.categories)
        assertEquals("https://alice.example/", note.authorUrl, "author falls back to the representative h-card")

        val article = feed.items[1]
        assertEquals("An article", article.data["name"])
        assertEquals(listOf("https://bob.example/post"), article.data["like-of"])
        assertEquals(listOf("https://alice.example/photo.jpg"), article.data["photo"])
        assertTrue((article.data["content"] as Map<*, *>)["html"].toString().contains("https://alice.example/link"))
        assertEquals(setOf("like", "photo"), article.kinds)
    }

    @Test
    fun `reads top-level h-entries without an h-feed`() {
        val single =
            """<article class="h-entry"><p class="p-name e-content">Just a note</p><a class="u-url" href="/n">x</a></article>"""
        val result = parser.parse(single, page)

        assertTrue(Mf2FeedParser.hasEntries(result))
        assertEquals(1, Mf2FeedParser.parse(result, page, null).items.size)
    }

    @Test
    fun `drops dates it cannot parse instead of passing them through`() {
        val doc =
            """
            <article class="h-entry"><p class="p-name e-content">Dated</p><a class="u-url" href="/n">x</a>
            <time class="dt-published" datetime="sometime last week">d</time>
            <time class="dt-updated" datetime="2026-11-02T10:00:00+02:00">d</time></article>
            """.trimIndent()
        val item = Mf2FeedParser.parse(parser.parse(doc, page), page, null).items.single()

        assertFalse(item.data.containsKey("published"))
        assertNull(item.published)
        assertEquals("2026-11-02T08:00:00Z", item.data["updated"])
    }
}
