package dev.jacobandersen.bastion.microsub.internal.web

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.indieauth.query.ValidatedAccessToken
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedHostValidator
import dev.jacobandersen.bastion.microsub.internal.job.EntryRetentionService
import dev.jacobandersen.bastion.microsub.internal.moderation.BlockWebmentionPolicy
import dev.jacobandersen.bastion.post.query.PostStateView
import dev.jacobandersen.bastion.webmention.events.ReceivedWebmentionChangedEvent
import dev.jacobandersen.bastion.webmention.internal.WebmentionService
import dev.jacobandersen.bastion.webmention.policy.OutboundWebmentionPolicy
import dev.jacobandersen.bastion.webmention.read.ReceivedWebmentionLookup
import dev.jacobandersen.bastion.webmention.read.WebmentionReadService
import dev.jacobandersen.bastion.webmention.read.WebmentionView
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
class MicrosubIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactions: TransactionTemplate

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var refresh: FeedRefreshService

    @Autowired
    lateinit var retention: EntryRetentionService

    @MockitoBean
    lateinit var tokens: AccessTokenQueryService

    @MockitoBean
    lateinit var hostValidator: FeedHostValidator

    @MockitoBean
    lateinit var webmentions: WebmentionReadService

    lateinit var mockMvc: MockMvc
    lateinit var server: HttpServer
    lateinit var base: String

    private val pages = mutableMapOf<String, Pair<String, String>>()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        whenever(tokens.resolve("full")).thenReturn(token("read", "follow", "mute", "block", "channels"))
        whenever(tokens.resolve("readonly")).thenReturn(token("read"))
        whenever(hostValidator.isBlocked(any())).thenReturn(false)

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

    private fun hEntry(
        slug: String,
        text: String,
        author: String,
    ): String =
        "<article class=\"h-entry\"><p class=\"e-content p-name\">$text</p><a class=\"u-url\" href=\"/$slug\">x</a>" +
            "<a class=\"p-author h-card\" href=\"$author\">$author</a>" +
            "<time class=\"dt-published\" datetime=\"2026-11-0${slug.last()}T10:00:00Z\">d</time>" +
            "<a class=\"p-category\" href=\"#\">tag-$slug</a></article>"

    private fun hFeed(vararg entries: String): String =
        "<html><body><div class=\"h-feed\"><span class=\"p-name\">Test feed</span>" +
            entries.joinToString("\n") +
            "</div></body></html>"

    private fun newChannel(): String {
        val created =
            json(
                mockMvc
                    .perform(post("/microsub").auth().param("action", "channels").param("name", "Reading ${UUID.randomUUID()}"))
                    .andExpect(status().isOk),
            )
        return created["uid"].asString()
    }

    @Test
    fun `requires a token and the right scope`() {
        mockMvc.perform(get("/microsub").param("action", "channels")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/microsub").param("action", "channels").auth("unknown")).andExpect(status().isForbidden)
        mockMvc
            .perform(post("/microsub").auth("readonly").param("action", "channels").param("name", "x"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient_scope"))
        mockMvc.perform(get("/microsub").auth("readonly").param("action", "channels")).andExpect(status().isOk)
    }

    @Test
    fun `manages channels with notifications first`() {
        val uid = newChannel()
        val second = newChannel()

        val channels = json(mockMvc.perform(get("/microsub").auth().param("action", "channels")).andExpect(status().isOk))["channels"]
        assertEquals("notifications", channels[0]["uid"].asString())
        val uids = channels.toList().map { it["uid"].asString() }
        assertTrue(uids.indexOf(uid) < uids.indexOf(second))

        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("method", "order")
                    .param("channels[]", second, uid),
            ).andExpect(status().isOk)
        val reordered =
            json(
                mockMvc.perform(get("/microsub").auth().param("action", "channels")),
            )["channels"].toList().map { it["uid"].asString() }
        assertTrue(reordered.indexOf(second) < reordered.indexOf(uid))
        assertEquals("notifications", reordered[0])

        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("channel", uid)
                    .param("name", "Renamed"),
            ).andExpect(jsonPath("$.uid").value(uid))
            .andExpect(jsonPath("$.name").value("Renamed"))
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("method", "delete")
                    .param("channel", "notifications"),
            ).andExpect(status().isBadRequest)
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("method", "delete")
                    .param("channel", uid),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", uid))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `follows a feed and pages, marks, searches and moderates its timeline`() {
        val alice = "https://alice.example/"
        val bob = "https://bob.example/"
        pages["/feed"] =
            "text/html" to
            hFeed(
                hEntry("p1", "Oldest entry about gardens", alice),
                hEntry("p2", "Middle entry about bikes", bob),
                hEntry("p3", "Newest entry about gardens", alice),
            )
        val channel = newChannel()

        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "follow")
                    .param("channel", channel)
                    .param("url", "$base/feed"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("feed"))
            .andExpect(jsonPath("$.name").value("Test feed"))
            .andExpect(jsonPath("$.format").value("mf2"))
            .andExpect(jsonPath("$.websub").doesNotExist())
        mockMvc
            .perform(get("/microsub").auth().param("action", "follow").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].format").value("mf2"))

        // Page through newest first, two at a time.
        val first =
            json(
                mockMvc.perform(
                    get("/microsub")
                        .auth()
                        .param("action", "timeline")
                        .param("channel", channel)
                        .param("limit", "2"),
                ),
            )
        assertEquals(2, first["items"].size())
        assertEquals("$base/p3", first["items"][0]["url"].asString())
        assertTrue(first["items"][0]["_is_read"].asBoolean(), "backfilled entries start read")
        assertEquals("$base/feed", first["items"][0]["_source"]["url"].asString())
        val after = first["paging"]["after"].asString()
        val before = first["paging"]["before"].asString()

        val second =
            json(
                mockMvc.perform(
                    get("/microsub")
                        .auth()
                        .param("action", "timeline")
                        .param("channel", channel)
                        .param("limit", "2")
                        .param("after", after),
                ),
            )
        assertEquals(listOf("$base/p1"), second["items"].toList().map { it["url"].asString() })
        assertFalse(second["paging"].has("after"), "no after cursor on the last page")

        val newer =
            json(
                mockMvc.perform(
                    get("/microsub")
                        .auth()
                        .param("action", "timeline")
                        .param("channel", channel)
                        .param("before", before),
                ),
            )
        assertEquals(0, newer["items"].size())
        assertFalse(newer["paging"].has("before"), "no before cursor without items")

        // Read state.
        val newestId = first["items"][0]["_id"].asString()
        val middleId = first["items"][1]["_id"].asString()
        mockMvc
            .perform(
                post(
                    "/microsub",
                ).auth()
                    .param(
                        "action",
                        "timeline",
                    ).param("method", "mark_unread")
                    .param("channel", channel)
                    .param("entry[]", newestId, middleId),
            ).andExpect(status().isOk)
        assertEquals(2, unread(channel))
        mockMvc
            .perform(
                post(
                    "/microsub",
                ).auth()
                    .param(
                        "action",
                        "timeline",
                    ).param("method", "mark_read")
                    .param("channel", channel)
                    .param("last_read_entry", middleId),
            ).andExpect(status().isOk)
        assertEquals(1, unread(channel))

        // Content search.
        mockMvc
            .perform(
                get("/microsub")
                    .auth()
                    .param("action", "search")
                    .param("channel", channel)
                    .param("query", "gardens"),
            ).andExpect(jsonPath("$.items", hasSize<Any>(2)))
        mockMvc
            .perform(
                get(
                    "/microsub",
                ).auth().param("action", "search").param("channel", "global").param("author", bob).param("category", "tag-p2"),
            ).andExpect(jsonPath("$.items[*].url", hasItem("$base/p2")))
        mockMvc.perform(get("/microsub").auth().param("action", "search").param("channel", channel)).andExpect(status().isBadRequest)

        // Mute hides, unmute restores; block deletes.
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "mute")
                    .param("channel", channel)
                    .param("url", alice),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
        mockMvc
            .perform(get("/microsub").auth().param("action", "mute").param("channel", channel))
            .andExpect(jsonPath("$.items[0].url").value(alice))
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "unmute")
                    .param("channel", channel)
                    .param("url", alice),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(3)))
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "block")
                    .param("channel", channel)
                    .param("url", bob),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", channel))
            .andExpect(jsonPath("$.items[*].url", not(hasItem("$base/p2"))))

        // Remove and unfollow.
        mockMvc
            .perform(
                post(
                    "/microsub",
                ).auth().param("action", "timeline").param("method", "remove").param("channel", channel).param("entry", newestId),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "unfollow")
                    .param("channel", channel)
                    .param("url", "$base/feed"),
            ).andExpect(status().isOk)
        mockMvc
            .perform(get("/microsub").auth().param("action", "follow").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))
    }

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

    private fun timeline(
        channel: String,
        vararg params: Pair<String, String>,
    ): JsonNode {
        val request = get("/microsub").auth().param("action", "timeline").param("channel", channel)
        params.forEach { (key, value) -> request.param(key, value) }
        return json(mockMvc.perform(request).andExpect(status().isOk))
    }

    private fun urls(page: JsonNode): List<String> = page["items"].toList().map { it["url"].asString() }

    @Test
    fun `pages forward from a before cursor without skipping entries`() {
        pages["/paged"] = "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/paged")
        val top = timeline(channel)["paging"]["before"].asString()

        pages["/paged"] =
            "text/html" to hFeed(*(1..5).map { hEntry("p$it", "Entry $it", "https://alice.example/") }.toTypedArray())
        refresh.refreshClaimed(feedId(channel))

        // Four new entries, limit two: the newest page comes first, with an `after` for the gap.
        val newest = timeline(channel, "before" to top, "limit" to "2")
        assertEquals(listOf("$base/p5", "$base/p4"), urls(newest))
        assertTrue(newest["paging"].has("before"), "items were returned, so before must be too")
        assertTrue(newest["paging"].has("after"), "more new entries exist than were returned")

        // The original before plus the new after returns what is left, and nothing more remains.
        val rest = timeline(channel, "before" to top, "after" to newest["paging"]["after"].asString(), "limit" to "2")
        assertEquals(listOf("$base/p3", "$base/p2"), urls(rest))
        assertFalse(rest["paging"].has("after"), "the gap is closed")
        assertTrue(rest["paging"].has("before"))

        // Polling with the newest before finds nothing new and returns no cursors.
        val none = timeline(channel, "before" to newest["paging"]["before"].asString(), "limit" to "2")
        assertEquals(0, none["items"].size())
        assertTrue(none["paging"].isEmpty)
    }

    @Test
    fun `a before poll finds a late entry that was published before the newest one`() {
        pages["/late"] = "text/html" to hFeed(hEntry("p5", "Newest", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/late")
        val top = timeline(channel)["paging"]["before"].asString()

        // p2 was published days before p5 but only shows up in the feed now.
        pages["/late"] =
            "text/html" to hFeed(hEntry("p5", "Newest", "https://alice.example/"), hEntry("p2", "Late", "https://alice.example/"))
        refresh.refreshClaimed(feedId(channel))

        assertEquals(listOf("$base/p2"), urls(timeline(channel, "before" to top)))
        assertEquals(listOf("$base/p2", "$base/p5"), urls(timeline(channel)))
    }

    @Test
    fun `a paging cursor from before arrival ordering still resolves`() {
        pages["/legacy"] =
            "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"), hEntry("p2", "Second", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/legacy")
        val page = timeline(channel, "limit" to "1")
        val id = page["items"][0]["_id"].asString()
        val micros =
            java.time.temporal.ChronoUnit.MICROS.between(
                java.time.Instant.EPOCH,
                java.time.Instant.parse(page["items"][0]["published"].asString()),
            )
        val legacy =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("$micros:$id".toByteArray())

        assertEquals(listOf("$base/p1"), urls(timeline(channel, "after" to legacy)))
    }

    @Test
    fun `a before poll that fits in one page carries no after cursor`() {
        pages["/poll"] = "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/poll")
        val top = timeline(channel)["paging"]["before"].asString()

        pages["/poll"] =
            "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"), hEntry("p2", "Second", "https://alice.example/"))
        refresh.refreshClaimed(feedId(channel))

        val poll = timeline(channel, "before" to top, "limit" to "5")
        assertEquals(listOf("$base/p2"), urls(poll))
        assertTrue(poll["paging"].has("before"))
        assertFalse(poll["paging"].has("after"))
    }

    @Test
    fun `pages with a before and after window newest first`() {
        pages["/window"] = "text/html" to hFeed(*(1..5).map { hEntry("p$it", "Entry $it", "https://alice.example/") }.toTypedArray())
        val channel = newChannel()
        follow(channel, "/window")

        // With limit=1 each page's single item is its own cursor, so walk p5..p1 and keep every cursor.
        val cursors = mutableListOf<String>()
        var after: String? = null
        repeat(5) {
            val page =
                if (after == null) timeline(channel, "limit" to "1") else timeline(channel, "limit" to "1", "after" to after!!)
            cursors += page["paging"]["before"].asString()
            after = page["paging"]["after"]?.asString()
        }
        assertNull(after, "the oldest entry has nothing older")

        // Strictly between p1 (cursors[4]) and p5 (cursors[0]): p4, p3, p2.
        val window = timeline(channel, "before" to cursors[4], "after" to cursors[0], "limit" to "10")
        assertEquals(listOf("$base/p4", "$base/p3", "$base/p2"), urls(window))
        assertFalse(window["paging"].has("after"))

        // A window larger than the limit keeps the newest and offers an after for the rest.
        val clipped = timeline(channel, "before" to cursors[4], "after" to cursors[0], "limit" to "2")
        assertEquals(listOf("$base/p4", "$base/p3"), urls(clipped))
        assertTrue(clipped["paging"].has("after"))
    }

    @Test
    fun `keeps entries from different feeds that share an item id apart`() {
        pages["/one"] = "text/html" to hFeed(hEntry("p1", "From feed one", "https://alice.example/"))
        pages["/two"] = "text/html" to hFeed(hEntry("p1", "From feed two", "https://bob.example/"))
        val channel = newChannel()
        follow(channel, "/one")
        follow(channel, "/two")

        val items = timeline(channel)["items"].toList()
        assertEquals(setOf("$base/one", "$base/two"), items.map { it["_source"]["url"].asString() }.toSet())
    }

    @Test
    fun `re-following a feed reattaches its kept entries instead of duplicating them`() {
        pages["/refollow"] =
            "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"), hEntry("p2", "Second", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/refollow")
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "unfollow")
                    .param("channel", channel)
                    .param("url", "$base/refollow"),
            ).andExpect(status().isOk)

        follow(channel, "/refollow")

        val items = timeline(channel)["items"].toList()
        assertEquals(listOf("$base/p2", "$base/p1"), items.map { it["url"].asString() })
        assertTrue(items.all { it["_source"]["url"].asString() == "$base/refollow" }, "kept entries belong to the new feed")
    }

    @Test
    fun `purges read entries only after they drop out of their feed`() {
        pages["/kept"] =
            "text/html" to hFeed(hEntry("p1", "Dropped", "https://alice.example/"), hEntry("p2", "Kept", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/kept")
        assertEquals(0, unread(channel), "backfilled entries start read")

        val longAgo = java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(60)))
        jdbc
            .sql(
                "update microsub_entries set updated_at = :at, last_seen_at = :at " +
                    "where channel_id = (select id from microsub_channels where uid = :uid)",
            ).param("at", longAgo)
            .param("uid", channel)
            .update()
        pages["/kept"] = "text/html" to hFeed(hEntry("p2", "Kept", "https://alice.example/"))
        refresh.refreshClaimed(feedId(channel))

        retention.purge()

        assertEquals(listOf("$base/p2"), urls(timeline(channel)))
        refresh.refreshClaimed(feedId(channel))
        assertEquals(0, unread(channel), "a re-fetch does not bring the kept entry back unread")
    }

    @Test
    fun `refresh fetches only stale feeds of the channel without waiting for the fetch`() {
        pages["/live"] = "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/live")
        pages["/live"] =
            "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"), hEntry("p2", "Second", "https://alice.example/"))

        fun requestRefresh(token: String = "readonly") = mockMvc.perform(post("/microsub/refresh").auth(token).param("channel", channel))

        requestRefresh().andExpect(status().isOk).andExpect(jsonPath("$.refreshing").value(0))
        assertEquals(0, unread(channel), "a just-fetched feed is not fetched again")

        val longAgo = java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1)))
        jdbc.sql("update microsub_feeds set last_fetched_at = :at").param("at", longAgo).update()
        requestRefresh().andExpect(jsonPath("$.refreshing").value(1))
        requestRefresh().andExpect(jsonPath("$.refreshing").value(0)) // claimed by the fetch just started
        assertEquals(0, unread(channel), "the request only queues the fetch")
        refresh.refreshClaimed(feedId(channel)) // the job server is off in tests, so run the queued job
        assertEquals(1, unread(channel))

        mockMvc
            .perform(post("/microsub/refresh").auth().param("channel", "nope"))
            .andExpect(status().isBadRequest)
        mockMvc
            .perform(post("/microsub/refresh").param("channel", channel))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(post("/microsub").auth().param("action", "refresh").param("channel", channel))
            .andExpect(status().isBadRequest) // not an action of the Microsub endpoint
    }

    @Test
    fun `streams an entries event when a fetch stores new entries`() {
        pages["/stream"] = "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"))
        val channel = newChannel()
        follow(channel, "/stream")

        val result = mockMvc.perform(get("/microsub/events").auth("readonly")).andReturn()
        val response = result.response
        await().untilAsserted { assertTrue(response.contentAsString.contains(":connected")) }
        assertEquals("no", response.getHeader("X-Accel-Buffering"))
        assertTrue(response.getHeader(HttpHeaders.CACHE_CONTROL).orEmpty().contains("no-cache"))
        assertTrue(response.contentType.orEmpty().startsWith("text/event-stream"))

        refresh.refreshClaimed(feedId(channel))
        assertFalse(response.contentAsString.contains("event:entries"), "an unchanged fetch announces nothing")

        pages["/stream"] =
            "text/html" to hFeed(hEntry("p1", "First", "https://alice.example/"), hEntry("p2", "Second", "https://alice.example/"))
        refresh.refreshClaimed(feedId(channel))
        await().untilAsserted {
            assertTrue(response.contentAsString.contains("event:entries"))
            assertTrue(response.contentAsString.contains("data:{\"channel\":\"$channel\",\"count\":1}"))
        }
        assertEquals(
            1,
            "event:entries".toRegex().findAll(response.contentAsString).count(),
            "only the fetch that stored a new entry is announced",
        )
    }

    @Test
    fun `the event stream needs a token`() {
        mockMvc.perform(get("/microsub/events")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `releases WebSub subscriptions when a channel is deleted`() {
        pages["/hubbed"] =
            "text/html" to
            "<html><head><link rel=\"hub\" href=\"https://hub.example/\"><link rel=\"self\" href=\"$base/hubbed\"></head>" +
            "<body><div class=\"h-feed\">${hEntry("p1", "Hubbed", "https://alice.example/")}</div></body></html>"
        val channel = newChannel()
        follow(channel, "/hubbed")
        val subscription =
            jdbc
                .sql("select websub_subscription_id from microsub_feeds where id = :id")
                .param("id", feedId(channel))
                .query(UUID::class.java)
                .single()
        assertNotNull(subscription)

        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "channels")
                    .param("method", "delete")
                    .param("channel", channel),
            ).andExpect(status().isOk)

        val state =
            jdbc
                .sql("select state from websub_subscriptions where id = :id")
                .param("id", subscription)
                .query(String::class.java)
                .optional()
        assertTrue(state.isEmpty || state.get() == "UNSUBSCRIBING", "subscription is released, state was $state")
    }

    @Test
    fun `discovers RSS alternates for search and preview`() {
        pages["/blog"] =
            "text/html" to
            """<html><head><title>Blog</title><link rel="alternate" type="application/rss+xml" href="/rss.xml" title="Blog RSS"></head><body>hi</body></html>"""
        pages["/rss.xml"] =
            "application/rss+xml" to
            """<?xml version="1.0"?><rss version="2.0"><channel><title>Blog RSS</title><link>$base/blog</link>
               <item><title>Hello</title><link>$base/hello</link><description>Hello there, world</description></item></channel></rss>"""

        val search =
            json(mockMvc.perform(post("/microsub").auth().param("action", "search").param("query", "$base/blog")).andExpect(status().isOk))
        assertEquals("$base/rss.xml", search["results"][0]["url"].asString())
        assertEquals("feed", search["results"][0]["type"].asString())

        mockMvc
            .perform(post("/microsub").auth().param("action", "preview").param("url", "$base/blog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].url").value("$base/hello"))
            .andExpect(jsonPath("$.items[0]._id").doesNotExist())

        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .param("action", "follow")
                    .param("channel", newChannel())
                    .param("url", "$base/missing"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `mirrors received webmentions into notifications`() {
        val webmentionId = UUID.randomUUID()
        val source = "https://carol.example/reply/${UUID.randomUUID()}"
        val view =
            WebmentionView(
                sourceUrl = source,
                targetUrl = "https://bastion.test/2026/11/05/post",
                interaction = "REPLY",
                authorName = "Carol",
                authorUrl = "https://carol.example/",
                authorPhoto = null,
                contentText = listOf("Nice post!"),
                contentHtml = emptyList(),
                firstSeenAt = Instant.now(),
                verifiedAt = Instant.now(),
                url = source,
                urlSameOrigin = true,
                inReplyTo = listOf("https://bastion.test/2026/11/05/post"),
                parentSourceUrl = null,
                viaSourceUrl = null,
            )
        whenever(webmentions.receivedById(webmentionId)).thenReturn(ReceivedWebmentionLookup.Displayed(view))
        publish(webmentionId)

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val items =
                json(
                    mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", "notifications")),
                )["items"]
            val item = items.firstOrNull { it["url"]?.asString() == source }
            assertNotNull(item)
            assertEquals("https://bastion.test/2026/11/05/post", item!!["in-reply-to"][0].asString())
            assertFalse(item["_is_read"].asBoolean())
        }

        whenever(webmentions.receivedById(webmentionId)).thenReturn(ReceivedWebmentionLookup.Unsettled)
        publish(webmentionId)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertEquals(0, pendingPublications(webmentionId), "the re-verification event was handled")
        }
        val kept =
            json(
                mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", "notifications")),
            )["items"]
        assertTrue(kept.any { it["url"]?.asString() == source }, "a webmention being re-verified keeps its notification")

        whenever(webmentions.receivedById(webmentionId)).thenReturn(ReceivedWebmentionLookup.Gone)
        publish(webmentionId)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val items =
                json(
                    mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", "notifications")),
                )["items"]
            assertTrue(items.none { it["url"]?.asString() == source })
        }
    }

    @Test
    fun `retries a first notification that arrives while the webmention is still being verified`() {
        val webmentionId = UUID.randomUUID()
        whenever(webmentions.receivedById(webmentionId)).thenReturn(ReceivedWebmentionLookup.Unsettled)
        publish(webmentionId)

        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertEquals(1, failedPublications(webmentionId), "the publication stays for the resubmission job")
            }
        } finally {
            jdbc
                .sql("delete from event_publication where serialized_event like :id")
                .param("id", "%$webmentionId%")
                .update()
        }
    }

    private fun failedPublications(webmentionId: UUID): Int =
        jdbc
            .sql("select count(*) from event_publication where status = 'FAILED' and serialized_event like :id")
            .param("id", "%$webmentionId%")
            .query(Int::class.java)
            .single()

    private fun pendingPublications(webmentionId: UUID): Int =
        jdbc
            .sql("select count(*) from event_publication where serialized_event like :id")
            .param("id", "%$webmentionId%")
            .query(Int::class.java)
            .single()

    private fun publish(webmentionId: UUID) {
        transactions.executeWithoutResult {
            events.publishEvent(ReceivedWebmentionChangedEvent(UUID.randomUUID(), webmentionId, UUID.randomUUID(), Instant.now()))
        }
    }

    private fun unread(channel: String): Int =
        json(mockMvc.perform(get("/microsub").auth().param("action", "channels")))["channels"]
            .first { it["uid"].asString() == channel }["unread"]
            .asInt()

    // --- spec-compliance regressions -------------------------------------------------------------------------------

    private fun action(vararg params: Pair<String, String>): ResultActions {
        val request = post("/microsub").auth()
        params.forEach { (key, value) -> request.param(key, value) }
        return mockMvc.perform(request)
    }

    private fun channelUids(): List<String> =
        json(mockMvc.perform(get("/microsub").auth().param("action", "channels")))["channels"].toList().map { it["uid"].asString() }

    private fun moderated(
        kind: String,
        channel: String,
    ): List<String> =
        json(mockMvc.perform(get("/microsub").auth().param("action", kind).param("channel", channel)).andExpect(status().isOk))["items"]
            .toList()
            .map { it["url"].asString() }

    @Test
    fun `never deletes the last channel besides notifications`() {
        jdbc.sql("delete from microsub_channels where uid <> 'notifications'").update()
        try {
            val only = newChannel()
            action("action" to "channels", "method" to "delete", "channel" to only)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("invalid_request"))
            assertEquals(listOf("notifications", only), channelUids())

            val other = newChannel()
            action("action" to "channels", "method" to "delete", "channel" to only).andExpect(status().isOk)
            action("action" to "channels", "method" to "delete", "channel" to other).andExpect(status().isBadRequest)
            assertEquals(listOf("notifications", other), channelUids())
        } finally {
            jdbc
                .sql(
                    """
                    insert into microsub_channels (id, uid, name, position, created_at, updated_at)
                    select gen_random_uuid(), 'home', 'Home', 1, now(), now()
                    where not exists (select 1 from microsub_channels where uid = 'home')
                    """.trimIndent(),
                ).update()
        }
    }

    @Test
    fun `global unmute and unblock undo the action in every channel`() {
        val alice = "https://alice.example/"
        val bob = "https://bob.example/"
        for ((add, remove, list) in listOf(Triple("mute", "unmute", "mute"), Triple("block", "unblock", "block"))) {
            val a = newChannel()
            val b = newChannel()
            for (channel in listOf(a, b, "global")) {
                action("action" to add, "channel" to channel, "url" to alice).andExpect(status().isOk)
            }
            action("action" to add, "channel" to a, "url" to bob).andExpect(status().isOk)

            // A channel-scoped undo only touches that channel.
            action("action" to remove, "channel" to a, "url" to alice).andExpect(status().isOk)
            assertEquals(listOf(bob), moderated(list, a))
            assertEquals(listOf(alice), moderated(list, b))
            assertEquals(listOf(alice), moderated(list, "global"))

            // `global` undoes it everywhere, but only for that URL.
            action("action" to remove, "channel" to "global", "url" to alice).andExpect(status().isOk)
            assertEquals(listOf(bob), moderated(list, a))
            assertEquals(emptyList<String>(), moderated(list, b))
            assertEquals(emptyList<String>(), moderated(list, "global"))

            // Undoing something that is not set is not an error.
            action("action" to remove, "channel" to "global", "url" to alice).andExpect(status().isOk)
        }
    }

    @Test
    fun `global unmute makes hidden entries visible again in every channel`() {
        val alice = "https://alice.example/"
        pages["/gm"] = "text/html" to hFeed(hEntry("p1", "Hidden note", alice))
        val channel = newChannel()
        follow(channel, "/gm")
        action("action" to "mute", "channel" to channel, "url" to alice).andExpect(status().isOk)
        assertEquals(0, timeline(channel)["items"].size())

        action("action" to "unmute", "channel" to "global", "url" to alice).andExpect(status().isOk)
        assertEquals(1, timeline(channel)["items"].size())
    }

    @Test
    fun `preview answers in the timeline format and stores nothing`() {
        pages["/prev"] = "text/html" to hFeed(hEntry("p1", "One", "https://alice.example/"), hEntry("p2", "Two", "https://alice.example/"))
        val stored = jdbc.sql("select count(*) from microsub_entries").query(Int::class.java).single()

        val preview = json(action("action" to "preview", "url" to "$base/prev").andExpect(status().isOk))
        assertEquals(2, preview["items"].size())
        assertTrue(preview["paging"].isObject && preview["paging"].isEmpty, "timeline format carries a (here empty) paging object")
        preview["items"].forEach { item ->
            assertEquals("entry", item["type"].asString())
            assertFalse(item["_is_read"].asBoolean())
            assertFalse(item.has("_id"), "an unstored entry has no internal id")
        }
        assertEquals("feed", preview["feed"]["type"].asString())

        val viaGet = json(mockMvc.perform(get("/microsub").auth().param("action", "preview").param("url", "$base/prev")))
        assertEquals(2, viaGet["items"].size())
        assertEquals(stored, jdbc.sql("select count(*) from microsub_entries").query(Int::class.java).single())
    }

    @Test
    fun `an author filter with no usable profile URL matches nothing`() {
        val alice = "https://alice.example/"
        val bob = "https://bob.example/"
        pages["/authors"] =
            "text/html" to
            hFeed(
                hEntry("p1", "garden one", alice),
                hEntry("p2", "garden two", bob),
                hEntry("p3", "garden three", alice),
            )
        val channel = newChannel()
        follow(channel, "/authors")

        fun search(vararg params: Pair<String, String>): JsonNode {
            val request = get("/microsub").auth().param("action", "search").param("channel", channel)
            params.forEach { (key, value) -> request.param(key, value) }
            return json(mockMvc.perform(request).andExpect(status().isOk))
        }

        assertEquals(3, search("query" to "garden")["items"].size())
        // ANDed with the query, an unusable author must not widen the result to every author.
        assertEquals(0, search("query" to "garden", "author" to "not a url")["items"].size())
        assertEquals(0, search("author" to "garbage")["items"].size(), "a lone bad author is a filter, not a missing filter")
        assertTrue(search("author" to "garbage")["paging"].isEmpty)
        // Repeated values are ORed, so a usable one still counts.
        assertEquals(2, search("author" to "garbage", "author" to alice)["items"].size())
        assertEquals(1, search("query" to "garden", "author" to bob)["items"].size())
    }

    @Test
    fun `a blank channel parameter is present, not absent`() {
        val before = channelUids()
        mockMvc
            .perform(
                get("/microsub")
                    .auth()
                    .param("action", "search")
                    .param("channel", "")
                    .param("query", "x"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
        action("action" to "channels", "channel" to "", "name" to "Blank Update").andExpect(status().isBadRequest)
        mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", "")).andExpect(status().isBadRequest)
        assertEquals(before, channelUids(), "no channel was created by the blank-channel update")
    }

    @Test
    fun `rejects entry ids it cannot have issued`() {
        val channel = newChannel()
        for (method in listOf("mark_read", "mark_unread", "remove")) {
            action("action" to "timeline", "method" to method, "channel" to channel, "entry" to "not-a-uuid")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("invalid_request"))
        }
        action("action" to "timeline", "method" to "mark_read", "channel" to channel, "last_read_entry" to "not-a-uuid")
            .andExpect(status().isBadRequest)
        // A well-formed id nobody has is simply nothing to do, so repeating a request stays safe.
        for (method in listOf("mark_read", "mark_unread", "remove")) {
            action("action" to "timeline", "method" to method, "channel" to channel, "entry" to UUID.randomUUID().toString())
                .andExpect(status().isOk)
        }
        action("action" to "timeline", "method" to "mark_read", "channel" to channel, "last_read_entry" to UUID.randomUUID().toString())
            .andExpect(status().isOk)
    }

    @Test
    fun `a timeline request without a channel reads the first channel after notifications`() {
        val first = channelUids().first { it != "notifications" }
        pages["/dflt"] = "text/html" to hFeed(hEntry("p1", "Default one", "https://alice.example/"))
        follow(first, "/dflt")

        val implicit = json(mockMvc.perform(get("/microsub").auth().param("action", "timeline")).andExpect(status().isOk))
        assertTrue(implicit["items"].size() > 0)
        assertEquals(timeline(first)["items"], implicit["items"])
    }

    @Test
    fun `reports every error in the Micropub error shape`() {
        mockMvc
            .perform(put("/microsub").auth().param("action", "channels"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET, HEAD, POST, OPTIONS"))
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.error_description").isNotEmpty)
        mockMvc
            .perform(delete("/microsub").auth().param("action", "channels"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.error").value("invalid_request"))
        mockMvc
            .perform(get("/microsub").auth().param("action", "timeline").param("channel", "no-such-channel"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.error_description").isNotEmpty)
        mockMvc
            .perform(get("/microsub").auth().param("action", "bogus"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
        mockMvc
            .perform(get("/microsub").auth())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
        mockMvc
            .perform(get("/microsub").param("action", "channels"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("unauthorized"))
        mockMvc
            .perform(get("/microsub").auth("unknown").param("action", "channels"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("forbidden"))
    }

    @Test
    fun `accepts an access token in a form body but never in the URL`() {
        // In the query string: not a recognized credential at all.
        mockMvc
            .perform(get("/microsub").queryParam("action", "channels").queryParam("access_token", "full"))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                post("/microsub")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .queryParam("access_token", "full")
                    .param("action", "channels")
                    .param("name", "x"),
            ).andExpect(status().isUnauthorized)
        // In a form-encoded POST body: accepted.
        mockMvc
            .perform(
                post("/microsub")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("access_token", "full")
                    .param("action", "channels")
                    .param("name", "Form token ${UUID.randomUUID()}"),
            ).andExpect(status().isOk)
        // A body token with some other content type is not read.
        mockMvc
            .perform(post("/microsub").param("access_token", "full").param("action", "channels").param("name", "x"))
            .andExpect(status().isUnauthorized)
        // Two forms at once: invalid_request.
        mockMvc
            .perform(
                post("/microsub")
                    .auth()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("access_token", "full")
                    .param("action", "channels")
                    .param("name", "x"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `matches scope names exactly`() {
        whenever(tokens.resolve("upper")).thenReturn(token("READ", "Follow"))
        mockMvc
            .perform(get("/microsub").auth("upper").param("action", "channels"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient_scope"))
    }

    @Test
    fun `every served entry has an ISO published and a bad date is never passed through`() {
        val note = "<article class=\"h-entry\"><p class=\"e-content p-name\">%s</p><a class=\"u-url\" href=\"/%s\">x</a>%s</article>"
        pages["/dates"] =
            "text/html" to
            hFeed(
                note.format("Undated note", "u1", ""),
                note.format("Bad date note", "u2", "<time class=\"dt-published\" datetime=\"not a date\">d</time>"),
                note.format("Good date note", "u3", "<time class=\"dt-published\" datetime=\"2026-11-02T10:00:00+02:00\">d</time>"),
            )
        val channel = newChannel()
        follow(channel, "/dates")

        val items = timeline(channel)["items"].toList().associateBy { it["url"].asString().substringAfterLast('/') }
        assertEquals(3, items.size)
        items.values.forEach { item ->
            val published = item["published"].asString()
            assertNotNull(java.time.OffsetDateTime.parse(published), "published must be ISO 8601: $published")
        }
        assertEquals("2026-11-02T08:00:00Z", items.getValue("u3")["published"].asString())
        assertNotEquals("not a date", items.getValue("u2")["published"].asString())
    }

    // --- blocks and outbound webmentions ---------------------------------------------------------------------------

    @Autowired
    lateinit var blockPolicy: BlockWebmentionPolicy

    @Autowired
    lateinit var policies: List<OutboundWebmentionPolicy>

    @Autowired
    lateinit var webmentionService: WebmentionService

    @Test
    fun `blocked users are suppressed as outbound webmention targets`() {
        val bob = "https://bob-policy.example/"
        val channel = newChannel()
        assertTrue(policies.contains(blockPolicy), "the policy is registered with the webmention module")
        assertFalse(blockPolicy.isSuppressed("https://bob-policy.example/post/1"))

        action("action" to "block", "channel" to channel, "url" to bob).andExpect(status().isOk)
        // Same site, any path, either scheme, any case, default port or not.
        assertTrue(blockPolicy.isSuppressed("https://bob-policy.example/post/1"))
        assertTrue(blockPolicy.isSuppressed("http://bob-policy.example/"))
        assertTrue(blockPolicy.isSuppressed("https://BOB-POLICY.example:443/x"))
        // Other sites, subdomains and other ports are different sites; non-URLs never match.
        assertFalse(blockPolicy.isSuppressed("https://sub.bob-policy.example/"))
        assertFalse(blockPolicy.isSuppressed("https://alice-policy.example/"))
        assertFalse(blockPolicy.isSuppressed("https://bob-policy.example:8443/"))
        assertFalse(blockPolicy.isSuppressed("garbage"))
        assertFalse(blockPolicy.isSuppressed("mailto:bob@bob-policy.example"))

        // A channel-scoped block counts, and unblocking in that channel lifts it.
        action("action" to "unblock", "channel" to channel, "url" to bob).andExpect(status().isOk)
        assertFalse(blockPolicy.isSuppressed("https://bob-policy.example/post/1"))

        // Global blocks count too, and a mute never does.
        action("action" to "mute", "channel" to "global", "url" to bob).andExpect(status().isOk)
        assertFalse(blockPolicy.isSuppressed("https://bob-policy.example/post/1"), "mutes have no outward effect")
        action("action" to "unmute", "channel" to "global", "url" to bob).andExpect(status().isOk)
        action("action" to "block", "channel" to "global", "url" to bob).andExpect(status().isOk)
        assertTrue(blockPolicy.isSuppressed("https://bob-policy.example/post/1"))
        action("action" to "unblock", "channel" to "global", "url" to bob).andExpect(status().isOk)
        assertFalse(blockPolicy.isSuppressed("https://bob-policy.example/post/1"))
    }

    @Test
    fun `a block stops outbound webmentions end to end and lifting it resumes them`() {
        val blocked = "https://blocked-e2e.example/page"
        val open = "https://open-e2e.example/page"
        val sourceUrl = "https://bastion.test/e2e/${UUID.randomUUID()}"
        val post =
            PostStateView(
                id = UUID.randomUUID(),
                slug = "e2e",
                url = sourceUrl,
                h = "h-entry",
                type = null,
                post =
                    Mf2Object(
                        type = listOf("h-entry"),
                        properties = mapOf("content" to listOf(Mf2Value.String("see $blocked and $open"))),
                        children = null,
                    ),
                deleted = false,
                publiclyReachable = true,
                isFeedVisible = true,
            )

        fun targets() =
            jdbc
                .sql("select target_url from webmention_notifications where source_url = :source")
                .param("source", sourceUrl)
                .query(String::class.java)
                .list()
                .toSet()

        // Notifications reference a real post.
        jdbc
            .sql(
                """
                insert into posts (id, slug, status, visibility, deleted, post, type)
                values (:id, :slug, 'PUBLISHED', 'PUBLIC', false, cast(:post as jsonb), 'note')
                """.trimIndent(),
            ).param("id", post.id)
            .param("slug", "e2e-${post.id}")
            .param(
                "post",
                """{"type":["h-entry"],"properties":{"content":["x"],"published":["2026-01-01T00:00:00Z"],"updated":["2026-01-01T00:00:00Z"]}}""",
            ).update()
        val channel = newChannel()
        try {
            action("action" to "block", "channel" to channel, "url" to "https://blocked-e2e.example/").andExpect(status().isOk)
            webmentionService.reconcile(post, refresh = true)
            assertEquals(setOf(open), targets(), "nothing is recorded or queued for the blocked site")

            action("action" to "unblock", "channel" to channel, "url" to "https://blocked-e2e.example/").andExpect(status().isOk)
            webmentionService.reconcile(post, refresh = true)
            assertEquals(setOf(open, blocked), targets(), "sending resumes at the next reconcile after unblocking")
        } finally {
            jdbc.sql("delete from webmention_notifications where source_url = :source").param("source", sourceUrl).update()
            jdbc.sql("delete from posts where id = :id").param("id", post.id).update()
        }
    }
}
