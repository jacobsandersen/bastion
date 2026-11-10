package dev.jacobandersen.bastion.reader.internal.web

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.indieauth.query.ValidatedAccessToken
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedHostValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
        "bastion.microsub.storage-cache-ttl=0s",
        "bastion.microsub.storage-warn-bytes=1",
    ],
)
class SavedIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var refresh: FeedRefreshService

    @MockitoBean
    lateinit var tokens: AccessTokenQueryService

    @MockitoBean
    lateinit var hostValidator: FeedHostValidator

    lateinit var mockMvc: MockMvc
    lateinit var server: HttpServer
    lateinit var base: String

    private val pages = mutableMapOf<String, Pair<String, String>>()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        whenever(tokens.resolve("full")).thenReturn(token("read", "follow", "mute", "block", "channels"))
        whenever(tokens.resolve("readonly")).thenReturn(token("read"))
        whenever(tokens.resolve("noread")).thenReturn(token("follow", "channels"))
        whenever(hostValidator.isBlocked(any())).thenReturn(false)
        jdbc.sql("delete from reader_saved_folders").update()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val page = pages[exchange.requestURI.path]
            if (page == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = page.second.toByteArray()
                exchange.responseHeaders.add("Content-Type", page.first)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun token(vararg scopes: String) =
        ValidatedAccessToken(
            "https://bastion.test/",
            "https://reader.example/",
            scopes.toList(),
            Instant.now(),
            Instant.now().plusSeconds(60),
        )

    private fun MockHttpServletRequestBuilder.auth(token: String = "full") = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private fun json(result: ResultActions): JsonNode = mapper.readTree(result.andReturn().response.contentAsString)

    private fun body(vararg pairs: Pair<String, Any?>): String = mapper.writeValueAsString(mapOf(*pairs))

    private fun hEntry(
        slug: String,
        text: String,
        author: String = "https://alice.example/",
    ): String =
        "<article class=\"h-entry\"><p class=\"e-content p-name\">$text</p><a class=\"u-url\" href=\"/$slug\">x</a>" +
            "<a class=\"p-author h-card\" href=\"$author\">alice</a>" +
            "<time class=\"dt-published\" datetime=\"2026-11-0${slug.last()}T10:00:00Z\">d</time></article>"

    private fun hFeed(vararg entries: String): String =
        "<html><body><div class=\"h-feed\"><span class=\"p-name\">Test feed</span>" + entries.joinToString("\n") + "</div></body></html>"

    private fun newChannel(): String =
        json(
            mockMvc
                .perform(post("/microsub").auth().param("action", "channels").param("name", "Reading ${UUID.randomUUID()}"))
                .andExpect(status().isOk),
        )["uid"].asString()

    private fun follow(
        channel: String,
        path: String,
    ) {
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "follow")
                    .param("channel", channel)
                    .param("url", "$base$path"),
            ).andExpect(status().isOk)
    }

    private fun feedId(channel: String): UUID =
        jdbc
            .sql("select f.id from microsub_feeds f join microsub_channels c on c.id = f.channel_id where c.uid = :uid")
            .param("uid", channel)
            .query(UUID::class.java)
            .single()

    /** The channel's entry ids by the slug of their url (`p1`, `p2`, ...). */
    private fun entries(channel: String): Map<String, String> =
        json(mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", channel)))["items"]
            .toList()
            .associate { it["url"].asString().substringAfterLast('/') to it["_id"].asString() }

    private fun microsubRemove(
        channel: String,
        id: String,
    ) = mockMvc
        .perform(
            post("/microsub")
                .auth()
                .param("action", "timeline")
                .param("method", "remove")
                .param("channel", channel)
                .param("entry", id),
        ).andExpect(status().isOk)

    private fun folder(name: String): String =
        json(
            mockMvc
                .perform(post("/reader/v1/saved/folders").auth().contentType(MediaType.APPLICATION_JSON).content(body("name" to name)))
                .andExpect(status().isCreated),
        )["uid"].asString()

    private fun save(
        folder: String,
        channel: String,
        entry: String,
    ): ResultActions =
        mockMvc.perform(
            post("/reader/v1/saved/items")
                .auth()
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("folder" to folder, "channel" to channel, "entry" to entry)),
        )

    private fun savedItems(folder: String? = null): List<JsonNode> =
        json(
            mockMvc
                .perform(get("/reader/v1/saved/items").auth().apply { folder?.let { param("folder", it) } })
                .andExpect(status().isOk),
        )["items"].toList()

    private fun version(folder: String): Long =
        json(mockMvc.perform(get("/reader/v1/saved/folders").auth()))["items"].first { it["uid"].asString() == folder }["version"].asLong()

    private fun savedRows(): Int = jdbc.sql("select count(*) from reader_saved_items").query(Int::class.java).single()

    private fun saved(
        channel: String,
        vararg slugs: String,
        into: String,
    ): List<String> {
        val ids = entries(channel)
        return slugs.map { json(save(into, channel, ids.getValue(it)).andExpect(status().is2xxSuccessful))["_id"].asString() }
    }

    // --- authentication and scopes -------------------------------------------------------------------------------------

    @Test
    fun `saved routes need a bearer token and the read scope, and folder changes also need channels`() {
        mockMvc.perform(get("/reader/v1/saved/folders")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/reader/v1/saved/folders").auth("noread")).andExpect(status().isForbidden)
        mockMvc.perform(get("/reader/v1/saved/items").auth("noread")).andExpect(status().isForbidden)
        mockMvc.perform(get("/reader/v1/saved/folders").auth("readonly")).andExpect(status().isOk)
        mockMvc
            .perform(post("/reader/v1/saved/folders").auth("readonly").contentType(MediaType.APPLICATION_JSON).content(body("name" to "X")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient_scope"))
        val uid = folder("Scopes")
        mockMvc
            .perform(
                patch("/reader/v1/saved/folders/$uid").auth("readonly").contentType(MediaType.APPLICATION_JSON).content(
                    body("name" to "Y"),
                ),
            ).andExpect(status().isForbidden)
        mockMvc.perform(delete("/reader/v1/saved/folders/$uid").auth("readonly")).andExpect(status().isForbidden)
        mockMvc.perform(delete("/reader/v1/saved/folders/$uid").auth("noread")).andExpect(status().isForbidden)
        assertEquals(1, json(mockMvc.perform(get("/reader/v1/saved/folders").auth("readonly")))["items"].size())
    }

    // --- folders -------------------------------------------------------------------------------------------------------

    @Test
    fun `folders are created with a slug uid, renamed without changing it, reordered and listed`() {
        val first =
            json(
                mockMvc
                    .perform(
                        post("/reader/v1/saved/folders").auth().contentType(MediaType.APPLICATION_JSON).content(
                            body("name" to " Read later "),
                        ),
                    ).andExpect(status().isCreated)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")),
            )
        assertEquals("read-later", first["uid"].asString())
        assertEquals("Read later", first["name"].asString())
        assertEquals(0, first["count"].asInt())
        assertEquals(0, first["version"].asInt())
        assertEquals("read-later-2", folder("Read later"), "a clashing uid gets a suffix")
        val third = folder("Recipes")
        mockMvc
            .perform(post("/reader/v1/saved/folders").auth().contentType(MediaType.APPLICATION_JSON).content(body("name" to "  ")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))

        mockMvc
            .perform(
                patch("/reader/v1/saved/folders/$third").auth().contentType(MediaType.APPLICATION_JSON).content(
                    body("name" to "Cooking"),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.uid").value(third))
            .andExpect(jsonPath("$.name").value("Cooking"))
        mockMvc
            .perform(patch("/reader/v1/saved/folders/$third").auth().contentType(MediaType.APPLICATION_JSON).content(body("position" to 0)))
            .andExpect(status().isOk)
        val listed = json(mockMvc.perform(get("/reader/v1/saved/folders").auth()))["items"].toList()
        assertEquals(listOf(third, "read-later", "read-later-2"), listed.map { it["uid"].asString() })
        assertEquals(listOf(0, 1, 2), listed.map { it["position"].asInt() })
        mockMvc
            .perform(patch("/reader/v1/saved/folders/nope").auth().contentType(MediaType.APPLICATION_JSON).content(body("name" to "Z")))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(patch("/reader/v1/saved/folders/$third").auth().contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }

    // --- saving --------------------------------------------------------------------------------------------------------

    @Test
    fun `saving copies the entry, is idempotent per url and shows the origin`() {
        pages["/a"] = "text/html" to hFeed(hEntry("p1", "Pasta recipe"), hEntry("p2", "Other"))
        val channel = newChannel()
        follow(channel, "/a")
        val folder = folder("Recipes")
        val entry = entries(channel).getValue("p1")

        val created =
            json(
                save(folder, channel, entry)
                    .andExpect(status().isCreated)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")),
            )
        assertEquals(folder, created["_folder"].asString())
        assertEquals("$base/p1", created["url"].asString())
        assertEquals("Pasta recipe", created["content"]["text"].asString())
        assertEquals(channel, created["_origin"]["channel"].asString())
        assertEquals(entry, created["_origin"]["entry"].asString())
        assertEquals("$base/a", created["_origin"]["feed"].asString())
        assertEquals("Test feed", created["_source"]["name"].asString())
        assertTrue(created["_saved_at"].asString().isNotBlank())
        assertFalse(created.has("_is_read"), "a saved item has no read state")
        assertNotEquals(entry, created["_id"].asString(), "the item has its own id")

        val again = json(save(folder, channel, entry).andExpect(status().isOk))
        assertEquals(created["_id"].asString(), again["_id"].asString())
        assertEquals(1, savedRows())

        // The same post arriving as another entry (here: the same url in a second channel) is still one item per folder.
        val other = newChannel()
        follow(other, "/a")
        json(save(folder, other, entries(other).getValue("p1")).andExpect(status().isOk))
        assertEquals(1, savedRows())
        // A second folder gets its own copy.
        val second = folder("Work")
        json(save(second, channel, entry).andExpect(status().isCreated))
        assertEquals(2, savedRows())

        val form =
            mockMvc
                .perform(
                    post("/reader/v1/saved/items")
                        .auth()
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("folder", second)
                        .param("channel", channel)
                        .param("entry", entries(channel).getValue("p2")),
                ).andExpect(status().isCreated)
        assertEquals("$base/p2", json(form)["url"].asString())
    }

    @Test
    fun `saving refuses unknown folders, channels and entries and needs the read scope`() {
        pages["/b"] = "text/html" to hFeed(hEntry("p1", "One"))
        val channel = newChannel()
        follow(channel, "/b")
        val folder = folder("Misc")
        val entry = entries(channel).getValue("p1")

        save("nope", channel, entry).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_request"))
        save(
            folder,
            channel,
            UUID.randomUUID().toString(),
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("entry_not_found"))
        save(folder, channel, "not-an-id").andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("entry_not_found"))
        save(folder, "nope", entry).andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("entry_not_found"))
        mockMvc
            .perform(post("/reader/v1/saved/items").auth().contentType(MediaType.APPLICATION_JSON).content(body("folder" to folder)))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(
                post("/reader/v1/saved/items")
                    .auth("noread")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("folder" to folder, "channel" to channel, "entry" to entry)),
            ).andExpect(status().isForbidden)
        assertEquals(0, savedRows())
    }

    // --- the guarantee: nothing in the entry lifecycle reaches a copy --------------------------------------------------------

    @Test
    fun `a saved copy survives removal, the feed dropping the entry, block, unfollow and channel delete`() {
        pages["/life"] =
            "text/html" to
            hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three", "https://spam.example/"), hEntry("p4", "Four"))
        val channel = newChannel()
        follow(channel, "/life")
        val folder = folder("Keep")
        val ids = entries(channel)

        saved(channel, "p1", "p3", "p4", into = folder)
        microsubRemove(channel, ids.getValue("p1"))
        // A deleted entry whose row is still kept can be rescued.
        microsubRemove(channel, ids.getValue("p2"))
        json(save(folder, channel, ids.getValue("p2")).andExpect(status().isCreated))
        assertEquals(4, savedRows())

        // The feed drops the removed items: their markers are hard-deleted, the copies stay.
        pages["/life"] = "text/html" to hFeed(hEntry("p3", "Three", "https://spam.example/"), hEntry("p4", "Four"))
        refresh.refreshClaimed(feedId(channel))
        assertEquals(
            0,
            jdbc
                .sql(
                    "select count(*) from microsub_entries where id in (:ids)",
                ).param(
                    "ids",
                    listOf(UUID.fromString(ids.getValue("p1")), UUID.fromString(ids.getValue("p2"))),
                ).query(Int::class.java)
                .single(),
            "the markers are gone",
        )
        assertEquals(4, savedRows())
        save(folder, channel, ids.getValue("p2")).andExpect(status().isNotFound)

        // A block deletes the author's stored entries; the saved copy stays.
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "block")
                    .param("channel", "global")
                    .param("url", "https://spam.example/"),
            ).andExpect(status().isOk)
        assertEquals(
            0,
            jdbc
                .sql("select count(*) from microsub_entries where url = :u")
                .param("u", "$base/p3")
                .query(Int::class.java)
                .single(),
        )
        assertEquals(4, savedRows())

        // Unfollow, then delete the channel (the entries cascade away).
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "unfollow")
                    .param("channel", channel)
                    .param("url", "$base/life"),
            ).andExpect(status().isOk)
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("method", "delete")
                    .param("channel", channel),
            ).andExpect(status().isOk)
        assertEquals(
            0,
            jdbc
                .sql("select count(*) from microsub_channels where uid = :u")
                .param("u", channel)
                .query(Int::class.java)
                .single(),
        )

        assertEquals(4, savedRows())
        val items = savedItems(folder)
        assertEquals(4, items.size)
        assertTrue(items.all { it["_origin"]["channel"].asString() == channel }, "the origin is plain text and outlives the channel")
        assertEquals("Three", items.first { it["url"].asString() == "$base/p3" }["content"]["text"].asString())
    }

    @Test
    fun `a webmention entry that is hard deleted leaves its saved copy`() {
        val channel = newChannel()
        val folder = folder("Mentions")
        val entry = UUID.randomUUID()
        val channelId =
            jdbc
                .sql("select id from microsub_channels where uid = :u")
                .param("u", channel)
                .query(UUID::class.java)
                .single()
        jdbc
            .sql(
                """
                insert into microsub_entries (id, channel_id, source_key, published, url, data, search, created_at, updated_at, last_seen_at)
                values (:id, :channel, 'webmention:1', now(), 'https://friend.example/reply', cast(:data as jsonb), to_tsvector('simple', 'hi'), now(), now(), now())
                """.trimIndent(),
            ).param("id", entry)
            .param("channel", channelId)
            .param("data", """{"url":"https://friend.example/reply","content":{"text":"hi"}}""")
            .update()
        json(save(folder, channel, entry.toString()).andExpect(status().isCreated))
        jdbc.sql("delete from microsub_entries where id = :id").param("id", entry).update()
        assertEquals(1, savedItems(folder).size)
    }

    @Test
    fun `bulk delete of everything leaves saved items alone`() {
        pages["/bulk"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        val channel = newChannel()
        follow(channel, "/bulk")
        val folder = folder("Safe")
        saved(channel, "p1", into = folder)
        val before = savedRows()

        val scope = mapOf("channel" to "global", "state" to "all", "published_before" to "2999-01-01T00:00:00Z")
        val preview =
            json(
                mockMvc
                    .perform(
                        post("/reader/v1/entries/delete")
                            .auth()
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(mapOf("scope" to scope))),
                    ).andExpect(status().isOk),
            )
        mockMvc
            .perform(
                post("/reader/v1/entries/delete")
                    .auth()
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            mapOf("scope" to scope, "dry_run" to false, "expected" to preview["matched"].asInt(), "confirm_unread" to true),
                        ),
                    ),
            ).andExpect(status().isOk)
        assertEquals(before, savedRows())
        assertEquals(1, savedItems(folder).size)
    }

    // --- reading, moving and removing items --------------------------------------------------------------------------------

    @Test
    fun `items page newest saved first with a cursor, a summary form and a single read`() {
        pages["/page"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/page")
        val folder = folder("Paged")
        val other = folder("Other")
        val ids = saved(channel, "p1", "p2", "p3", into = folder)
        saved(channel, "p1", into = other)

        assertEquals(listOf(ids[2], ids[1], ids[0]), savedItems(folder).map { it["_id"].asString() })
        assertEquals(4, savedItems().size, "without a folder every folder is read")

        val first =
            json(mockMvc.perform(get("/reader/v1/saved/items").auth().param("folder", folder).param("limit", "2")).andExpect(status().isOk))
        assertEquals(listOf(ids[2], ids[1]), first["items"].toList().map { it["_id"].asString() })
        val cursor = first["paging"]["after"].asString()
        val second =
            json(
                mockMvc.perform(
                    get("/reader/v1/saved/items")
                        .auth()
                        .param("folder", folder)
                        .param("limit", "2")
                        .param("after", cursor),
                ),
            )
        assertEquals(listOf(ids[0]), second["items"].toList().map { it["_id"].asString() })
        assertFalse(second["paging"].has("after"))

        val summary = json(mockMvc.perform(get("/reader/v1/saved/items").auth().param("folder", folder).param("summary", "ids")))["items"]
        assertEquals(setOf("id", "entry", "url", "saved_at"), summary[0].propertyNames().toSet())
        assertEquals("$base/p3", summary[0]["url"].asString())

        mockMvc
            .perform(get("/reader/v1/saved/items/${ids[0]}").auth())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$._id").value(ids[0]))
            .andExpect(jsonPath("$._folder").value(folder))
        mockMvc.perform(get("/reader/v1/saved/items/${UUID.randomUUID()}").auth()).andExpect(status().isNotFound)
        mockMvc.perform(get("/reader/v1/saved/items/nonsense").auth()).andExpect(status().isNotFound)
        mockMvc.perform(get("/reader/v1/saved/items").auth().param("folder", "nope")).andExpect(status().isBadRequest)
    }

    @Test
    fun `moving and removing items change the folder versions and are idempotent`() {
        pages["/mv"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        val channel = newChannel()
        follow(channel, "/mv")
        val a = folder("A")
        val b = folder("B")
        assertEquals(0, version(a))
        val ids = saved(channel, "p1", "p2", into = a)
        assertEquals(2, version(a), "each save bumps the folder")
        assertEquals(0, version(b))

        mockMvc
            .perform(patch("/reader/v1/saved/items/${ids[0]}").auth().contentType(MediaType.APPLICATION_JSON).content(body("folder" to b)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$._id").value(ids[0]))
            .andExpect(jsonPath("$._folder").value(b))
        assertEquals(3, version(a))
        assertEquals(1, version(b))
        assertEquals(listOf(ids[0]), savedItems(b).map { it["_id"].asString() })

        // Moving into a folder that already holds the post drops the source and returns the existing item.
        val duplicate = json(save(a, channel, entries(channel).getValue("p1")).andExpect(status().isCreated))["_id"].asString()
        val merged =
            json(
                mockMvc
                    .perform(
                        patch("/reader/v1/saved/items/$duplicate").auth().contentType(MediaType.APPLICATION_JSON).content(
                            body("folder" to b),
                        ),
                    ).andExpect(status().isOk),
            )
        assertEquals(ids[0], merged["_id"].asString())
        assertEquals(listOf(ids[1]), savedItems(a).map { it["_id"].asString() })
        mockMvc
            .perform(
                patch("/reader/v1/saved/items/${ids[0]}").auth().contentType(MediaType.APPLICATION_JSON).content(
                    body("folder" to "nope"),
                ),
            ).andExpect(status().isBadRequest)
        mockMvc
            .perform(
                patch("/reader/v1/saved/items/${UUID.randomUUID()}").auth().contentType(MediaType.APPLICATION_JSON).content(
                    body("folder" to b),
                ),
            ).andExpect(status().isNotFound)

        val before = version(a)
        mockMvc.perform(delete("/reader/v1/saved/items/${ids[1]}").auth("readonly")).andExpect(status().isOk)
        assertEquals(before + 1, version(a))
        mockMvc.perform(delete("/reader/v1/saved/items/${ids[1]}").auth("readonly")).andExpect(status().isOk)
        mockMvc.perform(delete("/reader/v1/saved/items/nonsense").auth("readonly")).andExpect(status().isOk)
        assertEquals(before + 1, version(a), "removing what is gone changes nothing")
        assertEquals(1, savedRows())
    }

    @Test
    fun `a batch moves or removes many items and skips unknown ids`() {
        pages["/batch"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/batch")
        val a = folder("A")
        val b = folder("B")
        val ids = saved(channel, "p1", "p2", "p3", into = a)

        fun batch(vararg pairs: Pair<String, Any?>) =
            mockMvc.perform(
                post("/reader/v1/saved/items/batch").auth("readonly").contentType(MediaType.APPLICATION_JSON).content(body(*pairs)),
            )

        batch("op" to "move", "ids" to listOf(ids[0], ids[1], UUID.randomUUID().toString(), "junk"), "folder" to b)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(2))
        assertEquals(setOf(ids[0], ids[1]), savedItems(b).map { it["_id"].asString() }.toSet())
        batch("op" to "move", "ids" to listOf(ids[0]), "folder" to b).andExpect(jsonPath("$.changed").value(0))
        batch("op" to "remove", "ids" to listOf(ids[0], ids[2], ids[2])).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(2))
        batch("op" to "remove", "ids" to listOf(ids[0])).andExpect(jsonPath("$.changed").value(0))
        assertEquals(1, savedRows())
        batch("op" to "move", "ids" to listOf(ids[1])).andExpect(status().isBadRequest)
        batch("op" to "move", "ids" to listOf(ids[1]), "folder" to "nope").andExpect(status().isBadRequest)
        batch("op" to "nuke", "ids" to listOf(ids[1])).andExpect(status().isBadRequest)
        batch("op" to "remove", "ids" to emptyList<String>()).andExpect(status().isBadRequest)
        batch("op" to "remove", "ids" to List(101) { UUID.randomUUID().toString() }).andExpect(status().isBadRequest)
        assertEquals(1, savedRows())
    }

    // --- search --------------------------------------------------------------------------------------------------------

    @Test
    fun `search finds saved items by their text across folders or within one, and pages`() {
        pages["/find"] =
            "text/html" to
            hFeed(
                hEntry("p1", "Sourdough starter recipe"),
                hEntry("p2", "Kotlin coroutines notes"),
                hEntry("p3", "Sourdough hydration chart"),
            )
        val channel = newChannel()
        follow(channel, "/find")
        val a = folder("A")
        val b = folder("B")
        val ids = saved(channel, "p1", "p2", into = a)
        val bread = saved(channel, "p3", into = b)

        fun search(
            q: String,
            folder: String? = null,
            limit: Int? = null,
            after: String? = null,
        ): JsonNode =
            json(
                mockMvc
                    .perform(
                        get("/reader/v1/saved/items")
                            .auth()
                            .param("q", q)
                            .apply {
                                folder?.let { param("folder", it) }
                                limit?.let { param("limit", it.toString()) }
                                after?.let { param("after", it) }
                            },
                    ).andExpect(status().isOk),
            )

        assertEquals(setOf(ids[0], bread[0]), search("sourdough")["items"].toList().map { it["_id"].asString() }.toSet())
        assertEquals(listOf(ids[0]), search("sourdough", folder = a)["items"].toList().map { it["_id"].asString() })
        assertEquals(listOf(ids[1]), search("kotlin coroutines")["items"].toList().map { it["_id"].asString() })
        assertEquals(listOf(bread[0]), search("sourdough -starter")["items"].toList().map { it["_id"].asString() })
        assertEquals(listOf(ids[1]), search("\"coroutines notes\" or nothingelse")["items"].toList().map { it["_id"].asString() })
        assertEquals(0, search("kotlin sourdough")["items"].size(), "words are ANDed")
        assertEquals(0, search("absent")["items"].size())
        assertEquals(3, search("   ")["items"].size(), "a blank query is no query")
        assertEquals(0, search("&|!:*(")["items"].size(), "punctuation is not query syntax")

        val first = search("sourdough", limit = 1)
        assertEquals(listOf(bread[0]), first["items"].toList().map { it["_id"].asString() })
        val second = search("sourdough", limit = 1, after = first["paging"]["after"].asString())
        assertEquals(listOf(ids[0]), second["items"].toList().map { it["_id"].asString() })

        // The author's name is searchable too, and a removed item is no longer found.
        assertEquals(3, search("alice")["items"].size())
        mockMvc.perform(delete("/reader/v1/saved/items/${ids[0]}").auth()).andExpect(status().isOk)
        assertEquals(listOf(bread[0]), search("sourdough")["items"].toList().map { it["_id"].asString() })
        mockMvc
            .perform(get("/reader/v1/saved/items").auth().param("q", "x").param("folder", "nope"))
            .andExpect(status().isBadRequest)
    }

    // --- storage -------------------------------------------------------------------------------------------------------

    private fun storage(): JsonNode = json(mockMvc.perform(get("/reader/v1/storage").auth("readonly")).andExpect(status().isOk))

    @Test
    fun `storage reports saved data as its own block and leaves the microsub figures alone`() {
        pages["/st"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        val channel = newChannel()
        follow(channel, "/st")
        val a = folder("Alpha")
        val b = folder("Beta")

        val empty = storage()["saved"]
        assertEquals(0, empty["items"].asInt())
        assertEquals(2, empty["folders"].asInt())
        assertEquals(0, empty["payload_bytes"].asLong())
        assertTrue(empty["on_disk_bytes"].asLong() > 0, "the empty tables and their indexes still occupy pages")

        val before = storage()["microsub"]
        saved(channel, "p1", "p2", into = a)
        saved(channel, "p1", into = b)
        val after = storage()

        val block = after["saved"]
        assertEquals(3, block["items"].asInt())
        assertEquals(2, block["folders"].asInt())
        assertTrue(block["payload_bytes"].asLong() > 0)
        assertTrue(block["on_disk_bytes"].asLong() >= block["payload_bytes"].asLong() / 2)
        val byFolder = block["by_folder"].toList().associateBy { it["uid"].asString() }
        assertEquals(2, byFolder.getValue(a)["items"].asInt())
        assertEquals("Alpha", byFolder.getValue(a)["name"].asString())
        assertEquals(1, byFolder.getValue(b)["items"].asInt())
        assertEquals(block["payload_bytes"].asLong(), byFolder.values.sumOf { it["bytes"].asLong() })
        assertTrue(byFolder.getValue(a)["bytes"].asLong() > byFolder.getValue(b)["bytes"].asLong())
        assertEquals(
            byFolder.getValue(a)["bytes"].asLong(),
            json(mockMvc.perform(get("/reader/v1/saved/folders").auth()))["items"].first { it["uid"].asString() == a }["bytes"].asLong(),
        )

        // Saving changes nothing in the microsub figures, whose on-disk size covers the microsub tables only.
        val microsub = after["microsub"]
        assertEquals(before["on_disk_bytes"].asLong(), microsub["on_disk_bytes"].asLong())
        assertEquals(before["payload_bytes"].asLong(), microsub["payload_bytes"].asLong())
        assertEquals(before["entries"].asLong(), microsub["entries"].asLong())
        assertEquals(before["unread"].asLong(), microsub["unread"].asLong())
        assertEquals(before["removed"].asLong(), microsub["removed"].asLong())
        assertEquals(1, after["warnings"].size(), "only the microsub threshold warns")
        assertTrue(after["database_bytes"].asLong() >= block["on_disk_bytes"].asLong())
        assertFalse(microsub.has("saved"))
    }

    // --- deleting folders ----------------------------------------------------------------------------------------------

    @Test
    fun `deleting a folder moves its items or deletes them only for the count the owner was shown`() {
        pages["/fd"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/fd")
        val a = folder("A")
        val b = folder("B")
        val c = folder("C")
        saved(channel, "p1", "p2", into = a)
        saved(channel, "p2", "p3", into = b)

        mockMvc
            .perform(delete("/reader/v1/saved/folders/$a").auth())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
        mockMvc
            .perform(delete("/reader/v1/saved/folders/$a").auth().param("items", "1"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("folder_changed"))
            .andExpect(jsonPath("$.items").value(2))
        mockMvc.perform(delete("/reader/v1/saved/folders/$a").auth().param("move_to", a)).andExpect(status().isBadRequest)
        mockMvc.perform(delete("/reader/v1/saved/folders/$a").auth().param("move_to", "nope")).andExpect(status().isBadRequest)
        mockMvc.perform(delete("/reader/v1/saved/folders/nope").auth().param("items", "0")).andExpect(status().isBadRequest)
        assertEquals(4, savedRows(), "refused deletes delete nothing")

        // Moving: p2 is in both folders, so the copy in A is dropped and p1 moves.
        mockMvc
            .perform(delete("/reader/v1/saved/folders/$a").auth().param("move_to", b))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moved").value(1))
            .andExpect(jsonPath("$.deleted").value(1))
        assertEquals(3, savedRows())
        assertEquals(setOf("$base/p1", "$base/p2", "$base/p3"), savedItems(b).map { it["url"].asString() }.toSet())

        mockMvc
            .perform(delete("/reader/v1/saved/folders/$b").auth().param("items", "3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(3))
        assertEquals(0, savedRows())
        mockMvc.perform(delete("/reader/v1/saved/folders/$c").auth()).andExpect(status().isOk)
        assertEquals(0, json(mockMvc.perform(get("/reader/v1/saved/folders").auth()))["items"].size())
    }
}
