package dev.jacobandersen.bastion.microsub.internal.web

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.indieauth.query.ValidatedAccessToken
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedHostValidator
import dev.jacobandersen.bastion.microsub.internal.job.EntryRetentionService
import dev.jacobandersen.bastion.webmention.events.ReceivedWebmentionChangedEvent
import dev.jacobandersen.bastion.webmention.read.ReceivedWebmentionLookup
import dev.jacobandersen.bastion.webmention.read.WebmentionReadService
import dev.jacobandersen.bastion.webmention.read.WebmentionView
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
        mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", uid)).andExpect(status().isNotFound)
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
        mockMvc
            .perform(get("/microsub").auth().param("action", "follow").param("channel", channel))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))

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

        val next = timeline(channel, "before" to top, "limit" to "2")
        assertEquals(listOf("$base/p3", "$base/p2"), urls(next))
        assertFalse(next["paging"].has("after"), "nothing older than the page is unseen")

        val last = timeline(channel, "before" to next["paging"]["before"].asString(), "limit" to "2")
        assertEquals(listOf("$base/p5", "$base/p4"), urls(last))

        val none = timeline(channel, "before" to last["paging"]["before"].asString(), "limit" to "2")
        assertEquals(0, none["items"].size())
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
}
