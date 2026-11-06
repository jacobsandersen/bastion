package dev.jacobandersen.bastion.microsub.internal.fetch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class JsonFeedParserTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `parses JSON Feed 1_1`() {
        val json =
            """
            {"version":"https://jsonfeed.org/version/1.1","title":"Dan","home_page_url":"https://dan.example/",
             "feed_url":"https://dan.example/feed.json","hubs":[{"type":"WebSub","url":"https://hub.example/"}],
             "authors":[{"name":"Dan","url":"https://dan.example/","avatar":"/dan.png"}],
             "items":[{"id":"1","url":"/posts/1","content_text":"Plain note","date_published":"2026-11-03T08:00:00+02:00",
                       "tags":["a","b"],"attachments":[{"url":"/v.mp4","mime_type":"video/mp4"}]}]}
            """.trimIndent()

        val feed = JsonFeedParser.parse(mapper, json.toByteArray(), "https://dan.example/feed.json", 50)!!

        assertEquals("Dan", feed.name)
        assertEquals(listOf("https://hub.example/"), feed.hubs)
        assertEquals("https://dan.example/feed.json", feed.self)
        val item = feed.items.single()
        assertEquals("https://dan.example/posts/1", item.url)
        assertEquals("2026-11-03T06:00:00Z", item.data["published"])
        assertEquals(mapOf("text" to "Plain note"), item.data["content"])
        assertEquals(listOf("https://dan.example/v.mp4"), item.data["video"])
        assertEquals("https://dan.example/", item.authorUrl)
    }

    @Test
    fun `ignores JSON that is not a JSON Feed`() {
        assertNull(JsonFeedParser.parse(mapper, """{"hello":"world"}""".toByteArray(), "https://x.example/", 50))
    }

    @Test
    fun `never emits an unparsed date and falls back to date_modified`() {
        fun item(fields: String) =
            JsonFeedParser
                .parse(
                    mapper,
                    """{"version":"https://jsonfeed.org/version/1.1","title":"T","items":[{"id":"1","url":"https://x.example/1",$fields}]}"""
                        .toByteArray(),
                    "https://x.example/feed.json",
                    50,
                )!!
                .items
                .single()

        val fallback = item(""""content_text":"a","date_published":"yesterday-ish","date_modified":"2026-11-03T08:00:00Z"""")
        assertEquals("2026-11-03T08:00:00Z", fallback.data["published"])

        val none = item(""""content_text":"a","date_published":"yesterday-ish","date_modified":"also bad"""")
        assertNull(none.data["published"])
        assertNull(none.data["updated"])
    }
}
