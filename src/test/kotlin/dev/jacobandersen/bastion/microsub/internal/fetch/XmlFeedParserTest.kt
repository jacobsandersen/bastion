package dev.jacobandersen.bastion.microsub.internal.fetch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmlFeedParserTest {
    @Test
    fun `parses RSS with atom hub and self links`() {
        val rss =
            """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel>
              <title>Bob's blog</title><link>https://bob.example/</link><description>Posts</description>
              <atom:link rel="hub" href="https://hub.example/"/>
              <atom:link rel="self" href="https://bob.example/feed.xml"/>
              <item>
                <title>First post</title><link>https://bob.example/1</link><guid>https://bob.example/1</guid>
                <pubDate>Sun, 01 Nov 2026 10:00:00 GMT</pubDate>
                <description>&lt;p&gt;Hi &lt;img src="/a.png" onerror="x()"&gt;&lt;/p&gt;</description>
                <category>news</category>
                <enclosure url="https://bob.example/ep.mp3" type="audio/mpeg" length="1"/>
              </item>
            </channel></rss>
            """.trimIndent()

        val feed = XmlFeedParser.parse(rss.toByteArray(), "https://bob.example/feed.xml", 50)

        assertEquals(FeedFormat.RSS, feed.format)
        assertEquals("Bob's blog", feed.name)
        assertEquals(listOf("https://hub.example/"), feed.hubs)
        assertEquals("https://bob.example/feed.xml", feed.self)
        val item = feed.items.single()
        assertEquals("First post", item.data["name"])
        assertEquals("2026-11-01T10:00:00Z", item.data["published"])
        val html = (item.data["content"] as Map<*, *>)["html"] as String
        assertTrue(html.contains("https://bob.example/a.png"))
        assertFalse(html.contains("onerror"))
        assertEquals(listOf("https://bob.example/ep.mp3"), item.data["audio"])
        assertEquals(listOf("news"), item.categories)
        assertEquals("https://bob.example/", item.authorUrl, "author falls back to the feed")
    }

    @Test
    fun `parses Atom and drops titles that repeat the content`() {
        val atom =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Carol</title><id>urn:carol</id><updated>2026-11-02T00:00:00Z</updated>
              <link rel="hub" href="https://hub.example/"/>
              <entry>
                <id>tag:carol,2026:1</id><title>A short note that goes on</title>
                <link href="https://carol.example/1"/><updated>2026-11-02T00:00:00Z</updated>
                <author><name>Carol</name><uri>https://carol.example/</uri></author>
                <content type="html">A short note that goes on and on.</content>
              </entry>
            </feed>
            """.trimIndent()

        val feed = XmlFeedParser.parse(atom.toByteArray(), "https://carol.example/atom", 50)

        assertEquals(FeedFormat.ATOM, feed.format)
        val item = feed.items.single()
        assertNull(item.data["name"])
        assertEquals("tag:carol,2026:1", item.sourceKey)
        assertEquals("https://carol.example/", item.authorUrl)
        assertEquals(listOf("https://hub.example/"), feed.hubs)
    }

    @Test
    fun `keeps breaks and links in Atom xhtml content with a prefixed namespace`() {
        val feed = XmlFeedParser.parse(resource("tantek-xhtml-entry.atom"), "https://tantek.com/updates.atom", 50)

        val item = feed.items.single()
        val content = item.data["content"] as Map<*, *>
        val html = content["html"] as String
        assertTrue(html.startsWith("faschadenfreude, noun<br><br>definition: joy"), html)
        assertTrue(html.contains("""<a href="https://www.vanityfair.com/story/midterms-republican-watch-party-blue-wave""""), html)
        assertTrue(html.contains("#<span>blueWave</span> #<span>FAFO</span>"), html)
        assertFalse(html.contains("default:"), html)
        assertFalse(html.contains("<div"), "the wrapping div is not part of the content")
        val text = content["text"] as String
        assertTrue(text.startsWith("faschadenfreude, noun\n\ndefinition: joy"), text)
        assertTrue(text.contains("see also FAFO.\n\ninspiration:\n* https://www.vanityfair.com/"), text)
        assertTrue(text.endsWith("#blueWave #FAFO"), text)
        assertNull(item.data["name"], "the xhtml title is an empty div")
    }

    @Test
    fun `reads Atom xhtml content with an unprefixed default namespace or none`() {
        val defaultNamespace =
            atomWithContent("""<div xmlns="http://www.w3.org/1999/xhtml"><p>One</p><p>Two <a href="/x">link</a><br/>three</p></div>""")
        val noNamespace = atomWithContent("<div><p>One</p><p>Two <a href=\"/x\">link</a><br/>three</p></div>")

        listOf(defaultNamespace, noNamespace).forEach { atom ->
            val content =
                XmlFeedParser
                    .parse(
                        atom.toByteArray(),
                        "https://dee.example/atom",
                        50,
                    ).items
                    .single()
                    .data["content"] as Map<*, *>
            assertEquals("""<p>One</p><p>Two <a href="https://dee.example/x">link</a><br>three</p>""", content["html"])
            assertEquals("One\n\nTwo link\nthree", content["text"])
        }
    }

    @Test
    fun `reads Atom xhtml content when the prefix is declared on the div or the feed`() {
        val onDiv = atomWithContent("""<h:div xmlns:h="http://www.w3.org/1999/xhtml"><h:p>One</h:p><h:ul><h:li>a</h:li></h:ul></h:div>""")
        val onFeed =
            atomWithContent("""<x:div><x:p>One</x:p><x:ul><x:li>a</x:li></x:ul></x:div>""")
                .replace("<feed ", """<feed xmlns:x="http://www.w3.org/1999/xhtml" """)

        listOf(onDiv, onFeed).forEach { atom ->
            val content =
                XmlFeedParser
                    .parse(
                        atom.toByteArray(),
                        "https://dee.example/atom",
                        50,
                    ).items
                    .single()
                    .data["content"] as Map<*, *>
            assertEquals("<p>One</p><ul><li>a</li></ul>", content["html"])
        }
    }

    @Test
    fun `does not treat other namespaces in Atom xhtml content as html`() {
        val atom = atomWithContent("""<div xmlns="http://www.w3.org/1999/xhtml">Hi<s:br xmlns:s="urn:other"/>there <b>you</b></div>""")

        val content =
            XmlFeedParser
                .parse(atom.toByteArray(), "https://dee.example/atom", 50)
                .items
                .single()
                .data["content"] as Map<*, *>

        assertEquals("Hithere <b>you</b>", content["html"])
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/microsub/$name")!!.readAllBytes()

    private fun atomWithContent(xhtml: String) =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Dee</title><id>urn:dee</id><updated>2026-11-02T00:00:00Z</updated>
          <entry>
            <id>tag:dee,2026:1</id><title>Dee</title><link href="https://dee.example/1"/><updated>2026-11-02T00:00:00Z</updated>
            <content type="xhtml">$xhtml</content>
          </entry>
        </feed>
        """.trimIndent()

    @Test
    fun `refuses DOCTYPE declarations`() {
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><title>&xxe;</title></channel></rss>
            """.trimIndent()

        assertThrows(Exception::class.java) { XmlFeedParser.parse(xxe.toByteArray(), "https://x.example/", 50) }
    }
}
