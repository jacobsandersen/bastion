package dev.jacobandersen.bastion.post.internal.types

import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class PostTypeCatalogTest {
    private val resourceLoader = DefaultResourceLoader()
    private val jsonMapper = JsonMapper.builderWithJackson2Defaults().build()

    private fun bundled(): PostTypeCatalog = PostTypeCatalog(PostTypesConfig(), resourceLoader, MockEnvironment())

    private fun configured(config: PostTypesConfig): PostTypeCatalog =
        PostTypeCatalog(
            config,
            resourceLoader,
            MockEnvironment().withProperty("bastion.post-types.roots[0]", "entry"),
        )

    private fun entry(properties: Map<String, List<Mf2Value>>): Mf2Object = Mf2Object(type = listOf("h-entry"), properties = properties)

    private fun strings(vararg values: String): List<Mf2Value> = values.map { Mf2Value.String(it) }

    private fun json(raw: String): JsonNode = jsonMapper.readTree(raw)

    private fun discover(
        catalog: PostTypeCatalog,
        properties: Map<String, List<Mf2Value>>,
    ): String? = catalog.discover(entry(properties))?.type

    @Test
    fun `bundled default classifies the IndieWeb post types`() {
        val catalog = bundled()

        assertEquals("rsvp", discover(catalog, mapOf("rsvp" to strings("yes"))))
        assertEquals("rsvp", discover(catalog, mapOf("rsvp" to listOf(Mf2Value.Json(json("""{"value":"maybe"}"""))))))
        assertEquals("reply", discover(catalog, mapOf("in-reply-to" to strings("https://example.com/a"))))
        assertEquals("repost", discover(catalog, mapOf("repost-of" to strings("https://example.com/a"))))
        assertEquals("like", discover(catalog, mapOf("like-of" to strings("https://example.com/a"))))
        assertEquals("video", discover(catalog, mapOf("video" to strings("https://example.com/a.mp4"))))
        assertEquals("photo", discover(catalog, mapOf("photo" to strings("https://example.com/a.jpg"))))
        assertEquals("article", discover(catalog, mapOf("name" to strings("Title"), "content" to strings("Body"))))
        assertEquals("note", discover(catalog, mapOf("content" to strings("Just a thought"))))
    }

    @Test
    fun `rsvp only matches the allowed values`() {
        val catalog = bundled()

        assertEquals("rsvp", discover(catalog, mapOf("rsvp" to strings("interested"))))
        assertEquals("note", discover(catalog, mapOf("rsvp" to strings("perhaps"), "content" to strings("body"))))
    }

    @Test
    fun `url rules require an absolute http or https uri`() {
        val catalog = bundled()

        assertEquals("reply", discover(catalog, mapOf("in-reply-to" to strings("http://localhost:8080/note"))))
        assertEquals(
            "reply",
            discover(
                catalog,
                mapOf(
                    "in-reply-to" to
                        listOf(Mf2Value.Json(json("""{"value":"https://example.com/a","url":"https://example.com/a"}"""))),
                ),
            ),
        )
        assertEquals("note", discover(catalog, mapOf("in-reply-to" to strings("example.com/a"), "content" to strings("body"))))
        assertEquals("note", discover(catalog, mapOf("in-reply-to" to strings("ftp://example.com/a"), "content" to strings("body"))))
        assertEquals("note", discover(catalog, mapOf("in-reply-to" to strings("//example.com/a"), "content" to strings("body"))))
    }

    @Test
    fun `name prefix of content classifies as a note`() {
        val catalog = bundled()

        assertEquals(
            "note",
            discover(catalog, mapOf("name" to strings("Hello"), "content" to strings("Hello world"))),
        )
        assertEquals(
            "article",
            discover(catalog, mapOf("name" to strings("Hello"), "content" to strings("An unrelated body"))),
        )
    }

    @Test
    fun `summary is used as the content fallback when comparing the name`() {
        val catalog = bundled()

        assertEquals(
            "note",
            discover(catalog, mapOf("name" to strings("Hello"), "summary" to strings("Hello everyone"))),
        )
        assertEquals(
            "article",
            discover(catalog, mapOf("name" to strings("Hello"), "summary" to strings("Something else"))),
        )
    }

    @Test
    fun `non-entry objects have no post type`() {
        val catalog = bundled()

        assertNull(
            catalog.discover(
                Mf2Object(type = listOf("h-card"), properties = mapOf("name" to strings("Sally"))),
            ),
        )
    }

    @Test
    fun `a custom type and rule work without code changes`() {
        val catalog = configured(customConfig())

        assertEquals(
            "recipe",
            discover(catalog, mapOf("recipe-ingredient" to strings("flour"))),
        )
        assertEquals(
            "note",
            discover(catalog, mapOf("content" to strings("Just a thought"))),
        )
        assertTrue(catalog.isKnownType("recipe"))
        assertFalse(catalog.isKnownType("unknown"))
    }

    @Test
    fun `syndication format inherits defaults and supports per-type overrides`() {
        val catalog = configured(customConfig())

        assertEquals(catalog.defaultSyndicationFormat, catalog.syndicationFormatFor("note"))
        assertEquals(catalog.defaultSyndicationFormat, catalog.syndicationFormatFor(null))
        assertTrue(catalog.syndicationFormatFor("article").format.usesName)
        assertFalse(catalog.syndicationFormatFor("note").format.usesName)
    }

    @Test
    fun `fingerprint tracks discovery rules but not syndication formats`() {
        val base = customConfig()
        val differentFormat =
            base.copy(
                defaults =
                    PostTypesConfig.Defaults(
                        SyndicationFormatConfig(format = "Read: {excerpt}\n\n{url}"),
                    ),
            )
        val differentRule =
            base.copy(
                discovery =
                    listOf(
                        PostTypesConfig.DiscoveryRule(
                            id = "note",
                            type = "note",
                            match = PostTypesConfig.RuleCondition(property = "content"),
                        ),
                    ),
            )

        assertEquals(configured(base).fingerprint, configured(differentFormat).fingerprint)
        assertNotEquals(configured(base).fingerprint, configured(differentRule).fingerprint)
    }

    @Test
    fun `an incomplete external configuration fails instead of merging the default`() {
        val environment = MockEnvironment().withProperty("bastion.post-types.roots[0]", "entry")

        assertThrows<IllegalArgumentException> {
            PostTypeCatalog(PostTypesConfig(), resourceLoader, environment)
        }
    }

    @Test
    fun `any external post-types property replaces the bundled default`() {
        val environment = MockEnvironment().withProperty("bastion.post-types.common-properties[0]", "content")

        val catalog = PostTypeCatalog(customConfig(), resourceLoader, environment)

        assertTrue(catalog.isKnownType("recipe"))
        assertFalse(catalog.isKnownType("video"))
    }

    @Test
    fun `yaml not conditions bind and classify`() {
        val yaml =
            """
            bastion:
              post-types:
                roots: [entry]
                fallback: note
                defaults:
                  syndication:
                    format: "{excerpt}\n\n{url}"
                types:
                  - type: note
                    properties: [name, content]
                  - type: article
                    properties: [name, content]
                discovery:
                  - id: article
                    type: article
                    match:
                      all:
                        - property: name
                          value:
                            text: true
                        - not:
                            name-prefix-of: [content]
            """.trimIndent()
        val sources = MutablePropertySources()
        YamlPropertySourceLoader().load("test", ByteArrayResource(yaml.toByteArray())).forEach(sources::addLast)
        val config =
            Binder(ConfigurationPropertySources.from(sources))
                .bind("bastion.post-types", Bindable.of(PostTypesConfig::class.java))
                .get()
        val catalog = configured(config)

        assertEquals(
            "note",
            discover(catalog, mapOf("name" to strings("Hello"), "content" to strings("Hello world"))),
        )
        assertEquals(
            "article",
            discover(catalog, mapOf("name" to strings("Hello"), "content" to strings("Other"))),
        )
    }

    private fun customConfig(): PostTypesConfig =
        PostTypesConfig(
            roots = listOf("entry"),
            fallback = "note",
            defaults = PostTypesConfig.Defaults(SyndicationFormatConfig(format = "{excerpt}\n\n{url}")),
            types =
                listOf(
                    PostTypesConfig.PostTypeDefinition(
                        type = "note",
                        name = "Note",
                        properties = listOf("content"),
                    ),
                    PostTypesConfig.PostTypeDefinition(
                        type = "article",
                        name = "Article",
                        properties = listOf("name", "content"),
                        syndication =
                            SyndicationFormatConfig(
                                format = "{name}: {excerpt}\n\n{url}",
                                fallbackFormat = "{excerpt}\n\n{url}",
                            ),
                    ),
                    PostTypesConfig.PostTypeDefinition(
                        type = "recipe",
                        name = "Recipe",
                        properties = listOf("recipe-ingredient"),
                    ),
                ),
            discovery =
                listOf(
                    PostTypesConfig.DiscoveryRule(
                        id = "recipe",
                        type = "recipe",
                        match =
                            PostTypesConfig.RuleCondition(
                                property = "recipe-ingredient",
                                value = PostTypesConfig.ValueCondition(text = true),
                            ),
                    ),
                    PostTypesConfig.DiscoveryRule(
                        id = "article",
                        type = "article",
                        match =
                            PostTypesConfig.RuleCondition(
                                all =
                                    listOf(
                                        PostTypesConfig.RuleCondition(
                                            property = "name",
                                            value = PostTypesConfig.ValueCondition(text = true),
                                        ),
                                        PostTypesConfig.RuleCondition(
                                            property = "content",
                                            value = PostTypesConfig.ValueCondition(text = true),
                                        ),
                                    ),
                            ),
                    ),
                ),
        )
}
