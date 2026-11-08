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
