package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.firstText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class SyndicationContentMapperTest {
    private val mapper = JsonMapper.builderWithJackson2Defaults().build()
    private val canonicalUrl = "https://bastion.test/2026/01/01/slug"

    private val noteFormat =
        SyndicationFormat.parse(
            "{excerpt}\n\n{url}",
            null,
            "{url}",
            "test note",
        )

    private val articleFormat =
        SyndicationFormat.parse(
            "{name}: {excerpt}\n\n{url}",
            "{excerpt}\n\n{url}",
            "{name}\n\n{url}",
            "test article",
        )

    private fun post(
        properties: Map<String, List<Mf2Value>>,
        type: String? = null,
    ) = Post(
        id = UUID.randomUUID(),
        slug = "slug",
        status = PostStatus.PUBLISHED,
        visibility = PostVisibility.PUBLIC,
        h = "h-entry",
        type = type,
        post = Mf2Object(type = listOf("h-entry"), properties = properties, children = null),
    )

    private fun contentOf(mapped: Mf2Object): String = mapped.firstText("content")!!

    @Test
    fun `short note appends the permalink`() {
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(Mf2Value.String("Hello world")))),
                canonicalUrl,
                300,
                noteFormat,
            )

        assertEquals("Hello world\n\n$canonicalUrl", contentOf(mapped))
        assertEquals(canonicalUrl, mapped.firstText("url"))
    }

    @Test
    fun `long note truncates at a word boundary within budget`() {
        val body = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor " + "word ".repeat(100)
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(Mf2Value.String(body)))),
                canonicalUrl,
                300,
                noteFormat,
            )
        val content = contentOf(mapped)

        assertTrue(content.endsWith(canonicalUrl))
        assertTrue(content.contains("…"))
        assertTrue(Graphemes.count(content) <= 300)
        assertTrue("word word" in content || "lorem" in content)
    }

    @Test
    fun `article leads with the title and a colon`() {
        val mapped =
            SyndicationContentMapper.build(
                post(
                    mapOf(
                        "name" to listOf(Mf2Value.String("My Title")),
                        "content" to listOf(Mf2Value.String("Some body text here")),
                    ),
                    type = "article",
                ),
                canonicalUrl,
                300,
                articleFormat,
            )
        val content = contentOf(mapped)

        assertTrue(content.startsWith("My Title: "))
        assertTrue(content.endsWith(canonicalUrl))
        assertTrue(Graphemes.count(content) <= 300)
    }

    @Test
    fun `article excerpt shrinks so title plus link fit the budget`() {
        val body = "word ".repeat(200)
        val mapped =
            SyndicationContentMapper.build(
                post(
                    mapOf(
                        "name" to listOf(Mf2Value.String("A fairly long article title")),
                        "content" to listOf(Mf2Value.String(body)),
                    ),
                    type = "article",
                ),
                canonicalUrl,
                120,
                articleFormat,
            )
        val content = contentOf(mapped)

        assertTrue(content.startsWith("A fairly long article title: "))
        assertTrue(content.endsWith(canonicalUrl))
        assertTrue(Graphemes.count(content) <= 120)
    }

    @Test
    fun `article without room for an excerpt falls back to title plus link`() {
        val mapped =
            SyndicationContentMapper.build(
                post(
                    mapOf(
                        "name" to listOf(Mf2Value.String("A title")),
                        "content" to listOf(Mf2Value.String("word ".repeat(200))),
                    ),
                    type = "article",
                ),
                canonicalUrl,
                60,
                articleFormat,
            )
        val content = contentOf(mapped)

        assertTrue(content.endsWith(canonicalUrl))
        assertTrue(Graphemes.count(content) <= 60)
    }

    @Test
    fun `permalink is never dropped even when the budget is tiny`() {
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(Mf2Value.String("word ".repeat(50))))),
                canonicalUrl,
                10,
                noteFormat,
            )

        assertEquals(canonicalUrl, contentOf(mapped))
    }

    @Test
    fun `summary is replaced so full text does not leak through it`() {
        val mapped =
            SyndicationContentMapper.build(
                post(
                    mapOf(
                        "content" to listOf(Mf2Value.String("word ".repeat(200))),
                        "summary" to listOf(Mf2Value.String("the full summary that should not be sent verbatim " + "x".repeat(500))),
                    ),
                ),
                canonicalUrl,
                300,
                noteFormat,
            )

        assertEquals(contentOf(mapped), mapped.firstText("summary"))
        assertTrue(Graphemes.count(mapped.firstText("summary")!!) <= 300)
    }

    @Test
    fun `html content collapses to plain text`() {
        val html = Mf2Value.Json(mapper.readTree("""{"html": "<p>Hello <strong>world</strong></p>", "value": "Hello world"}"""))
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(html))),
                canonicalUrl,
                300,
                noteFormat,
            )

        assertEquals("Hello world\n\n$canonicalUrl", contentOf(mapped))
    }

    @Test
    fun `emoji survives truncation intact`() {
        val body = "word 🇺🇸 ".repeat(100)
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(Mf2Value.String(body)))),
                canonicalUrl,
                100,
                noteFormat,
            )
        val content = contentOf(mapped)

        assertTrue(Graphemes.count(content) <= 100)
        assertTrue(content.endsWith(canonicalUrl))
        assertTrue(!content.contains("�"))
    }

    @Test
    fun `note without content falls back to the name`() {
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("name" to listOf(Mf2Value.String("Just a title")))),
                canonicalUrl,
                300,
                noteFormat,
            )

        assertEquals("Just a title\n\n$canonicalUrl", contentOf(mapped))
    }
}
