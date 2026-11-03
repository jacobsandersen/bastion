package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.ExcerptConfig
import dev.jacobandersen.bastion.post.internal.types.ExcerptSettings
import dev.jacobandersen.bastion.post.internal.types.ExcerptTruncation
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.firstText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    private fun excerptFormat(
        format: String = "{excerpt}\n\n{url}",
        fallback: String? = null,
        overflow: String = "{url}",
        excerpt: ExcerptConfig = ExcerptConfig.DEFAULT,
    ) = SyndicationFormat.parse(format, fallback, overflow, "test", excerpt)

    @Test
    fun `excerpt sources are tried in order and the first usable one wins`() {
        val format = excerptFormat(excerpt = ExcerptConfig.DEFAULT.copy(sources = listOf("summary", "content")))
        val withSummary =
            post(
                mapOf(
                    "summary" to listOf(Mf2Value.String("A short summary")),
                    "content" to listOf(Mf2Value.String("The long body")),
                ),
            )
        val withoutSummary = post(mapOf("content" to listOf(Mf2Value.String("The long body"))))

        assertEquals("A short summary\n\n$canonicalUrl", contentOf(SyndicationContentMapper.build(withSummary, canonicalUrl, 300, format)))
        assertEquals("The long body\n\n$canonicalUrl", contentOf(SyndicationContentMapper.build(withoutSummary, canonicalUrl, 300, format)))
    }

    @Test
    fun `html only content is stripped to plain text`() {
        val html = Mf2Value.Json(mapper.readTree("""{"html": "<p>Hello <strong>world</strong> &amp; co</p><p>Second</p>"}"""))
        val mapped = SyndicationContentMapper.build(post(mapOf("content" to listOf(html))), canonicalUrl, 300, noteFormat)

        assertEquals("Hello world & co Second\n\n$canonicalUrl", contentOf(mapped))
    }

    @Test
    fun `custom ellipsis and grapheme truncation are honored`() {
        val format =
            excerptFormat(
                excerpt = ExcerptConfig.DEFAULT.copy(ellipsis = "...", truncate = ExcerptTruncation.GRAPHEME),
            )
        val mapped =
            SyndicationContentMapper.build(
                post(mapOf("content" to listOf(Mf2Value.String("abcdefghij ".repeat(30))))),
                canonicalUrl,
                100,
                format,
            )
        val content = contentOf(mapped)

        assertTrue(content.contains("..."))
        assertTrue(!content.contains("…"))
        assertEquals(100, Graphemes.count(content))
    }

    @Test
    fun `min graphemes controls when the overflow format takes over`() {
        val body = post(mapOf("content" to listOf(Mf2Value.String("word ".repeat(50)))))
        val strict = excerptFormat(excerpt = ExcerptConfig.DEFAULT.copy(minGraphemes = 50))
        val lenient = excerptFormat(excerpt = ExcerptConfig.DEFAULT.copy(minGraphemes = 5))

        val tight = Graphemes.count(canonicalUrl) + 2 + 30
        assertEquals(canonicalUrl, contentOf(SyndicationContentMapper.build(body, canonicalUrl, tight, strict)))
        assertTrue(contentOf(SyndicationContentMapper.build(body, canonicalUrl, tight, lenient)).length > canonicalUrl.length)
    }

    @Test
    fun `summary tags and arbitrary property placeholders render`() {
        val format =
            excerptFormat(
                format = "{summary} {tags} {prop:in-reply-to}: {excerpt}\n\n{url}",
                fallback = "{excerpt}\n\n{url}",
            )
        val full =
            post(
                mapOf(
                    "summary" to listOf(Mf2Value.String("Sum")),
                    "category" to listOf(Mf2Value.String("kotlin"), Mf2Value.String("my tag")),
                    "in-reply-to" to listOf(Mf2Value.String("https://example.com/p")),
                    "content" to listOf(Mf2Value.String("Body")),
                ),
            )

        assertEquals(
            "Sum #kotlin #mytag https://example.com/p: Body\n\n$canonicalUrl",
            contentOf(SyndicationContentMapper.build(full, canonicalUrl, 300, format)),
        )
    }

    @Test
    fun `fallback format is used when any optional placeholder is missing`() {
        val format = excerptFormat(format = "{tags}: {excerpt}\n\n{url}", fallback = "{excerpt}\n\n{url}")
        val untagged = post(mapOf("content" to listOf(Mf2Value.String("Body"))))

        assertEquals("Body\n\n$canonicalUrl", contentOf(SyndicationContentMapper.build(untagged, canonicalUrl, 300, format)))
    }

    @Test
    fun `invalid templates and excerpt settings fail fast`() {
        assertThrows<IllegalArgumentException> { excerptFormat(format = "{tags} {excerpt} {url}") }
        assertThrows<IllegalArgumentException> { excerptFormat(format = "{prop:Bad Key} {excerpt}", fallback = "{excerpt}") }
        assertThrows<IllegalArgumentException> { excerptFormat(format = "{bogus} {excerpt}", fallback = "{excerpt}") }
        assertThrows<IllegalArgumentException> { excerptFormat(format = "{tags} {excerpt}", fallback = "{tags} {excerpt}") }
        assertThrows<IllegalArgumentException> {
            ExcerptConfig.resolve(ExcerptSettings(sources = emptyList()), null, "test")
        }
        assertThrows<IllegalArgumentException> {
            ExcerptConfig.resolve(ExcerptSettings(minGraphemes = -1), null, "test")
        }
    }

    @Test
    fun `excerpt settings inherit defaults field by field`() {
        val resolved =
            ExcerptConfig.resolve(
                ExcerptSettings(sources = listOf("summary"), ellipsis = "~"),
                ExcerptSettings(minGraphemes = 5),
                "test",
            )

        assertEquals(listOf("summary"), resolved.sources)
        assertEquals("~", resolved.ellipsis)
        assertEquals(5, resolved.minGraphemes)
        assertEquals(ExcerptTruncation.WORD, resolved.truncate)
    }
}
