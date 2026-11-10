package dev.jacobandersen.bastion.reader.internal.web

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.indieauth.query.ValidatedAccessToken
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedHostValidator
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
        "bastion.microsub.storage-cache-ttl=0s",
        "bastion.microsub.storage-warn-bytes=1",
    ],
)
class ReaderIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var refresh: FeedRefreshService

    @Autowired
    lateinit var dataSource: DataSource

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

    private fun MockHttpServletRequestBuilder.auth(token: String = "readonly") = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private fun json(result: ResultActions): JsonNode = mapper.readTree(result.andReturn().response.contentAsString)

    private fun hEntry(
        slug: String,
        text: String,
    ): String =
        "<article class=\"h-entry\"><p class=\"e-content p-name\">$text</p><a class=\"u-url\" href=\"/$slug\">x</a>" +
            "<a class=\"p-author h-card\" href=\"https://alice.example/\">alice</a>" +
            "<time class=\"dt-published\" datetime=\"2026-11-0${slug.last()}T10:00:00Z\">d</time></article>"

    private fun hFeed(vararg entries: String): String =
        "<html><body><div class=\"h-feed\"><span class=\"p-name\">Test feed</span>" + entries.joinToString("\n") + "</div></body></html>"

    private fun newChannel(): String =
        json(
            mockMvc
                .perform(
                    post("/microsub")
                        .auth("full")
                        .param("action", "channels")
                        .param("name", "Reading ${UUID.randomUUID()}"),
                ).andExpect(status().isOk),
        )["uid"].asString()

    private fun follow(
        channel: String,
        path: String,
    ) {
        mockMvc
            .perform(
                post("/microsub")
                    .auth("full")
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

    private fun unread(channel: String): Int =
        json(mockMvc.perform(get("/microsub").auth().param("action", "channels")))["channels"]
            .first { it["uid"].asString() == channel }["unread"]
            .asInt()

    // --- discovery -------------------------------------------------------------------------------------------------

    @Test
    fun `the IndieAuth metadata advertises the reader API and keeps its standard members`() {
        mockMvc
            .perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bastion_reader_api").value("https://bastion.test/reader/v1"))
            .andExpect(jsonPath("$.issuer").value("https://bastion.test/"))
            .andExpect(jsonPath("$.token_endpoint").value("https://bastion.test/indieauth/token"))
            .andExpect(jsonPath("$.scopes_supported", hasItems("read", "channels")))
            .andExpect(jsonPath("$.extensions").doesNotExist())
    }

    @Test
    fun `the capability document is public, cacheable and lists the features`() {
        mockMvc
            .perform(get("/reader/v1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.api").value("bastion-reader"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.microsub_endpoint").value("https://bastion.test/microsub"))
            .andExpect(jsonPath("$.features", hasSize<Any>(9)))
            .andExpect(
                jsonPath(
                    "$.features",
                    hasItems(
                        "refresh",
                        "events",
                        "feed-details",
                        "entry-check",
                        "deleted-entries",
                        "bulk-delete",
                        "unread-totals",
                        "storage",
                        "saved",
                    ),
                ),
            ).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        // A browser client on another origin reads it too, and so can a token holder.
        mockMvc
            .perform(get("/reader/v1").header(HttpHeaders.ORIGIN, "https://reader.example"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://reader.example"))
        mockMvc.perform(get("/reader/v1").auth()).andExpect(status().isOk)
    }

    // --- authentication --------------------------------------------------------------------------------------------

    @Test
    fun `every other route needs a Bearer header token with the read scope`() {
        val channel = newChannel()
        // Builders are mutable, so each request is built fresh.
        val routes: List<() -> MockHttpServletRequestBuilder> =
            listOf(
                { post("/reader/v1/channels/$channel/refresh") },
                { get("/reader/v1/channels/$channel/feeds") },
                {
                    post(
                        "/reader/v1/channels/$channel/entries/check",
                    ).contentType(MediaType.APPLICATION_JSON).content("""{"entry":["x"]}""")
                },
                { get("/reader/v1/events").accept(MediaType.TEXT_EVENT_STREAM) },
                { get("/reader/v1/unread") },
                { get("/reader/v1/storage") },
            )
        routes.forEach { route ->
            mockMvc
                .perform(route())
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("unauthorized"))
        }
        // The token must be in the header: a form body or query value is not accepted.
        mockMvc
            .perform(
                post(
                    "/reader/v1/channels/$channel/refresh",
                ).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("access_token", "readonly"),
            ).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/reader/v1/channels/$channel/feeds").param("access_token", "readonly")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                get("/reader/v1/channels/$channel/feeds").header(HttpHeaders.AUTHORIZATION, "Basic abc"),
            ).andExpect(status().isUnauthorized)

        routes.forEach { route ->
            mockMvc.perform(route().auth("unknown")).andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("forbidden"))
            mockMvc
                .perform(route().auth("noread"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.error").value("insufficient_scope"))
        }
    }

    @Test
    fun `answers a CORS preflight without a token`() {
        mockMvc
            .perform(
                options("/reader/v1/channels/x/refresh")
                    .header(HttpHeaders.ORIGIN, "https://reader.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://reader.example"))
    }

    // --- refresh ---------------------------------------------------------------------------------------------------

    @Test
    fun `refresh fetches only stale feeds of the channel without waiting for the fetch`() {
        pages["/live"] = "text/html" to hFeed(hEntry("p1", "First"))
        val channel = newChannel()
        follow(channel, "/live")
        pages["/live"] = "text/html" to hFeed(hEntry("p1", "First"), hEntry("p2", "Second"))

        fun requestRefresh() = mockMvc.perform(post("/reader/v1/channels/$channel/refresh").auth())

        requestRefresh().andExpect(status().isOk).andExpect(jsonPath("$.refreshing").value(0))
        assertEquals(0, unread(channel), "a just-fetched feed is not fetched again")

        val longAgo = java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1)))
        jdbc.sql("update microsub_feeds set last_fetched_at = :at").param("at", longAgo).update()
        requestRefresh().andExpect(jsonPath("$.refreshing").value(1))
        requestRefresh().andExpect(jsonPath("$.refreshing").value(0)) // claimed by the fetch just started
        assertEquals(0, unread(channel), "the request only queues the fetch")
        refresh.refreshClaimed(feedId(channel)) // the job server is off in tests, so run the queued job
        assertEquals(1, unread(channel))
    }

    @Test
    fun `refresh of an unknown channel is a 404, not Microsub's 400`() {
        mockMvc
            .perform(post("/reader/v1/channels/nope/refresh").auth())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    // --- feed details ----------------------------------------------------------------------------------------------

    @Test
    fun `lists the channel's feeds with their format and WebSub state`() {
        pages["/plain"] = "text/html" to hFeed(hEntry("p1", "Plain"))
        pages["/hubbed"] =
            "text/html" to
            "<html><head><link rel=\"hub\" href=\"https://hub.example/\"><link rel=\"self\" href=\"$base/hubbed\"></head>" +
            "<body><div class=\"h-feed\"><span class=\"p-name\">Hubbed</span>${hEntry("p1", "Hubbed")}</div></body></html>"
        val channel = newChannel()
        follow(channel, "/plain")
        follow(channel, "/hubbed")

        val items = json(mockMvc.perform(get("/reader/v1/channels/$channel/feeds").auth()).andExpect(status().isOk))["items"]
        assertEquals(2, items.size())
        val plain = items.first { it["url"].asString() == "$base/plain" }
        assertEquals("Test feed", plain["name"].asString())
        assertEquals("mf2", plain["format"].asString())
        assertFalse(plain.has("websub"), "a feed with no hub has no WebSub state")
        assertFalse(plain.has("photo"), "absent values are omitted")
        val hubbed = items.first { it["url"].asString() == "$base/hubbed" }
        assertEquals("pending", hubbed["websub"].asString())

        mockMvc
            .perform(get("/reader/v1/channels/nope/feeds").auth())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    // --- entry check -----------------------------------------------------------------------------------------------

    private fun entryIds(channel: String): List<String> =
        json(mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", channel)))["items"]
            .toList()
            .map { it["_id"].asString() }

    private fun check(
        channel: String,
        vararg ids: String,
    ): ResultActions =
        mockMvc.perform(
            post("/reader/v1/channels/$channel/entries/check")
                .auth()
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("entry" to ids.toList()))),
        )

    private fun gone(
        channel: String,
        vararg ids: String,
    ): List<String> = json(check(channel, *ids).andExpect(status().isOk))["gone"].toList().map { it.asString() }

    @Test
    fun `the entry check reports removed, hard-deleted, foreign and malformed ids as gone`() {
        pages["/check"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        pages["/other"] = "text/html" to hFeed(hEntry("p4", "Four"))
        val channel = newChannel()
        val otherChannel = newChannel()
        follow(channel, "/check")
        follow(otherChannel, "/other")
        val (kept, removed, deleted) = entryIds(channel)
        val foreign = entryIds(otherChannel).single()

        // `remove` marks the row; the row stays, but the entry is gone for the client.
        mockMvc
            .perform(
                post(
                    "/microsub",
                ).auth().param("action", "timeline").param("method", "remove").param("channel", channel).param("entry", removed),
            ).andExpect(status().isOk)
        jdbc.sql("delete from microsub_entries where id = :id").param("id", UUID.fromString(deleted)).update()
        val never = UUID.randomUUID().toString()

        assertEquals(
            listOf(removed, deleted, never, foreign, "not-an-id"),
            gone(channel, kept, removed, deleted, never, foreign, "not-an-id"),
        )
        assertEquals(emptyList<String>(), gone(channel, kept))
        assertEquals(listOf(removed), gone(channel, removed, kept, removed, " $kept "), "duplicates collapse, whitespace is trimmed")
        assertEquals(emptyList<String>(), gone(otherChannel, foreign))
    }

    @Test
    fun `the entry check accepts form-encoded ids and validates its input`() {
        pages["/validate"] = "text/html" to hFeed(hEntry("p1", "One"))
        val channel = newChannel()
        follow(channel, "/validate")
        val id = entryIds(channel).single()
        val missing = UUID.randomUUID().toString()

        fun form(vararg params: Pair<String, String>) =
            post("/reader/v1/channels/$channel/entries/check").auth().contentType(MediaType.APPLICATION_FORM_URLENCODED).also { request ->
                params.forEach { (name, value) -> request.param(name, value) }
            }

        mockMvc
            .perform(form("entry[]" to id, "entry[]" to missing))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gone[0]").value(missing))
            .andExpect(jsonPath("$.gone", hasSize<Any>(1)))
        mockMvc.perform(form("entry" to missing)).andExpect(status().isOk).andExpect(jsonPath("$.gone[0]").value(missing))

        mockMvc.perform(form()).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_request"))
        check(channel).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_request"))
        mockMvc
            .perform(
                post("/reader/v1/channels/$channel/entries/check").auth().contentType(MediaType.APPLICATION_JSON).content("{}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))

        val max = List(500) { UUID.randomUUID().toString() }.toTypedArray()
        assertEquals(500, gone(channel, *max).size)
        check(channel, *max, UUID.randomUUID().toString())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))

        check("nope", id).andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("not_found"))
    }

    // --- unread and storage --------------------------------------------------------------------------------------------

    private fun remove(
        channel: String,
        id: String,
    ) = mockMvc
        .perform(
            post(
                "/microsub",
            ).auth("full").param("action", "timeline").param("method", "remove").param("channel", channel).param("entry", id),
        ).andExpect(status().isOk)

    private fun unreadBody(): JsonNode =
        json(
            mockMvc
                .perform(get("/reader/v1/unread").auth())
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")),
        )

    private fun JsonNode.channelUnread(uid: String) = this["channels"].first { it["uid"].asString() == uid }["unread"].asInt()

    @Test
    fun `unread totals count per channel and feed, skip notifications in the total and ignore deleted and moderated entries`() {
        pages["/u1"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        pages["/u2"] = "text/html" to hFeed(hEntry("p4", "Four"))
        val channel = newChannel()
        val other = newChannel()
        follow(channel, "/u1")
        follow(other, "/u2")
        // Following backfills entries as read; make them unread like fresh arrivals.
        jdbc
            .sql("update microsub_entries set is_read = false where channel_id in (select id from microsub_channels where uid in (:uids))")
            .param("uids", listOf(channel, other))
            .update()
        val baseline = unreadBody()
        val others = baseline["total"].asInt() - 4

        assertEquals(3, baseline.channelUnread(channel))
        assertEquals(1, baseline.channelUnread(other))
        assertEquals(3, baseline["feeds"].first { it["url"].asString() == "$base/u1" }["unread"].asInt())
        assertEquals(channel, baseline["feeds"].first { it["url"].asString() == "$base/u1" }["channel"].asString())

        // The server-wide total leaves notifications out; the channel list still carries it.
        val notifications = jdbc.sql("select id from microsub_channels where uid = 'notifications'").query(UUID::class.java).single()
        jdbc
            .sql(
                "insert into microsub_entries (id, channel_id, source_key, published, data, created_at, updated_at, last_seen_at) " +
                    "values (:id, :channel, :key, now(), '{}'::jsonb, now(), now(), now())",
            ).param("id", UUID.randomUUID())
            .param("channel", notifications)
            .param("key", "webmention:${UUID.randomUUID()}")
            .update()
        val withNotification = unreadBody()
        assertEquals(1, withNotification.channelUnread("notifications"))
        assertEquals(baseline["total"].asInt(), withNotification["total"].asInt())
        assertEquals("notifications", withNotification["channels"][0]["uid"].asString(), "channels keep Microsub's order")

        // Deleted entries are not unread; muted authors are hidden exactly as `action=channels` hides them.
        remove(channel, entryIds(channel).first())
        assertEquals(2, unreadBody().channelUnread(channel))
        assertEquals(2, unread(channel), "the Microsub channel list agrees")
        assertEquals(others + 3, unreadBody()["total"].asInt())
        mockMvc
            .perform(
                post("/microsub")
                    .auth("full")
                    .param("action", "mute")
                    .param("channel", other)
                    .param("url", "https://alice.example/"),
            ).andExpect(status().isOk)
        val muted = unreadBody()
        assertEquals(0, muted.channelUnread(other))
        assertEquals(
            0,
            muted["feeds"]
                .first {
                    it["url"].asString() == "$base/u2"
                }["unread"]
                .asInt(),
            "a followed feed with nothing unread is listed",
        )
        assertEquals(unread(other), muted.channelUnread(other))
    }

    @Test
    fun `storage reports counts and bytes per channel and feed, separates deleted markers and warns past a threshold`() {
        pages["/s1"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/s1")
        jdbc
            .sql("update microsub_entries set is_read = false where channel_id = (select id from microsub_channels where uid = :uid)")
            .param("uid", channel)
            .update()
        remove(channel, entryIds(channel).first())

        val body =
            json(
                mockMvc
                    .perform(get("/reader/v1/storage").auth())
                    .andExpect(status().isOk)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.ttl_seconds").value(0))
                    .andExpect(jsonPath("$.computed_at").isString),
            )
        val microsub = body["microsub"]
        val mine = microsub["channels"].first { it["uid"].asString() == channel }
        assertEquals(2, mine["entries"].asInt(), "deleted entries are not counted as entries")
        assertEquals(2, mine["unread"].asInt(), "and are not unread")
        assertEquals(1, mine["removed"].asInt(), "they are reported separately")
        assertTrue(mine["bytes"].asLong() > 0)
        val feed = microsub["feeds"].first { it["url"].asString() == "$base/s1" }
        assertEquals(channel, feed["channel"].asString())
        assertEquals(2, feed["entries"].asInt())
        assertEquals(1, feed["removed"].asInt())
        assertEquals(mine["bytes"].asLong(), feed["bytes"].asLong())
        assertTrue(microsub["entries"].asLong() >= 2)
        assertTrue(microsub["removed"].asLong() >= 1)
        assertTrue(microsub["payload_bytes"].asLong() >= mine["bytes"].asLong())
        assertTrue(microsub["on_disk_bytes"].asLong() > 0)
        assertTrue(body["database_bytes"].asLong() >= microsub["on_disk_bytes"].asLong())

        // The test context sets a 1 byte limit and no entry limit.
        assertEquals(1, body["warnings"].size())
        assertEquals("bytes_high", body["warnings"][0]["code"].asString())
        assertEquals(1, body["warnings"][0]["threshold"].asLong())
        assertEquals(microsub["on_disk_bytes"].asLong(), body["warnings"][0]["value"].asLong())
    }

    // --- live stream -----------------------------------------------------------------------------------------------

    @Test
    fun `streams an entries event when a fetch stores new entries`() {
        pages["/stream"] = "text/html" to hFeed(hEntry("p1", "First"))
        val channel = newChannel()
        follow(channel, "/stream")

        val result = mockMvc.perform(get("/reader/v1/events").auth().accept(MediaType.TEXT_EVENT_STREAM)).andReturn()
        val response = result.response
        await().untilAsserted { assertTrue(response.contentAsString.contains(":connected")) }
        assertEquals("no", response.getHeader("X-Accel-Buffering"))
        assertTrue(response.getHeader(HttpHeaders.CACHE_CONTROL).orEmpty().contains("no-cache"))
        assertTrue(response.contentType.orEmpty().startsWith("text/event-stream"))

        refresh.refreshClaimed(feedId(channel))
        assertFalse(response.contentAsString.contains("event:entries"), "an unchanged fetch announces nothing")

        pages["/stream"] = "text/html" to hFeed(hEntry("p1", "First"), hEntry("p2", "Second"))
        refresh.refreshClaimed(feedId(channel))
        await().untilAsserted {
            assertTrue(response.contentAsString.contains("event:entries"))
            assertTrue(response.contentAsString.contains("data:{\"channel\":\"$channel\",\"count\":1}"))
        }
    }

    // --- deleted entries ---------------------------------------------------------------------------------------------

    @Test
    fun `lists deleted entries with a note that they are permanently deleted once their feed drops them`() {
        pages["/del"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/del")
        val items = json(mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", channel)))["items"].toList()
        assertEquals(0, json(mockMvc.perform(get("/reader/v1/channels/$channel/deleted").auth()).andExpect(status().isOk))["items"].size())
        for (slug in listOf("p1", "p2", "p3")) {
            val id = items.first { it["url"].asString() == "$base/$slug" }["_id"].asString()
            mockMvc
                .perform(
                    post(
                        "/microsub",
                    ).auth("full").param("action", "timeline").param("method", "remove").param("channel", channel).param("entry", id),
                ).andExpect(status().isOk)
            Thread.sleep(5)
        }

        val first =
            json(
                mockMvc
                    .perform(get("/reader/v1/channels/$channel/deleted").auth().param("limit", "2"))
                    .andExpect(status().isOk)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("permanently deleted"))),
            )
        assertEquals(listOf("$base/p3", "$base/p2"), first["items"].toList().map { it["url"].asString() }, "most recently deleted first")
        assertTrue(first["items"][0]["_deleted_at"].asString().isNotBlank())
        assertEquals("Three", first["items"][0]["content"]["text"].asString(), "the content is still there")
        assertEquals("$base/del", first["items"][0]["_source"]["url"].asString())
        val after = first["paging"]["after"].asString()

        val second =
            json(
                mockMvc
                    .perform(
                        get("/reader/v1/channels/$channel/deleted").auth().param("limit", "2").param("after", after),
                    ).andExpect(status().isOk),
            )
        assertEquals(listOf("$base/p1"), second["items"].toList().map { it["url"].asString() })
        assertFalse(second["paging"].has("after"), "no cursor on the last page")
        assertTrue(second["note"].asString().contains("permanently deleted"))

        // Once the feed stops listing an entry it is gone from this list too.
        pages["/del"] = "text/html" to hFeed(hEntry("p2", "Two"), hEntry("p3", "Three"))
        refresh.refreshClaimed(feedId(channel))
        val remaining = json(mockMvc.perform(get("/reader/v1/channels/$channel/deleted").auth()).andExpect(status().isOk))
        assertEquals(listOf("$base/p3", "$base/p2"), remaining["items"].toList().map { it["url"].asString() })
    }

    @Test
    fun `the deleted entries list needs the read scope and an existing channel`() {
        mockMvc.perform(get("/reader/v1/channels/home/deleted")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/reader/v1/channels/home/deleted").auth("noread")).andExpect(status().isForbidden)
        mockMvc
            .perform(get("/reader/v1/channels/nope/deleted").auth())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    // --- the legacy Microsub extras are untouched --------------------------------------------------------------------

    // --- bulk delete -------------------------------------------------------------------------------------------------

    private fun bulk(
        scope: Map<String, Any?>?,
        dryRun: Boolean? = null,
        expected: Int? = null,
        confirmUnread: Boolean? = null,
        token: String = "full",
    ): ResultActions =
        mockMvc.perform(
            post("/reader/v1/entries/delete")
                .auth(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        buildMap {
                            scope?.let { put("scope", it) }
                            dryRun?.let { put("dry_run", it) }
                            expected?.let { put("expected", it) }
                            confirmUnread?.let { put("confirm_unread", it) }
                        },
                    ),
                ),
        )

    private fun dryRun(scope: Map<String, Any?>): JsonNode = json(bulk(scope).andExpect(status().isOk))

    private fun execute(
        scope: Map<String, Any?>,
        expected: Int,
        confirmUnread: Boolean? = null,
    ): ResultActions = bulk(scope, dryRun = false, expected = expected, confirmUnread = confirmUnread)

    private fun rows(channel: String): Int =
        jdbc
            .sql("select count(*) from microsub_entries e join microsub_channels c on c.id = e.channel_id where c.uid = :uid")
            .param("uid", channel)
            .query(Int::class.java)
            .single()

    private fun liveRows(channel: String): Int =
        jdbc
            .sql(
                "select count(*) from microsub_entries e join microsub_channels c on c.id = e.channel_id where c.uid = :uid and e.removed_at is null",
            ).param("uid", channel)
            .query(Int::class.java)
            .single()

    @Test
    fun `bulk delete needs the read and channels scopes and a bearer token`() {
        val scope = mapOf("channel" to "home")
        mockMvc
            .perform(post("/reader/v1/entries/delete").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        bulk(scope, token = "readonly")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("insufficient_scope"))
            .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString("channels")))
        bulk(
            scope,
            token = "noread",
        ).andExpect(status().isForbidden).andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString("read")))
    }

    @Test
    fun `a dry run reports the count, size and range and deletes nothing`() {
        pages["/dry"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/dry")

        val all = dryRun(mapOf("channel" to channel))
        assertEquals(3, all["matched"].asInt())
        assertEquals(0, all["unread"].asInt())
        assertEquals(0, all["permanent"].asInt())
        assertTrue(all["bytes"].asLong() > 0)
        assertEquals("2026-11-01T10:00:00Z", all["oldest"].asString())
        assertEquals("2026-11-03T10:00:00Z", all["newest"].asString())
        assertEquals(channel, all["by_channel"].single()["uid"].asString())
        assertEquals(3, all["by_channel"].single()["matched"].asInt())

        val older = dryRun(mapOf("channel" to channel, "published_before" to "2026-11-03T00:00:00Z"))
        assertEquals(2, older["matched"].asInt(), "age counts by the published date")
        assertEquals(3, dryRun(mapOf("channel" to channel, "feed" to "$base/dry"))["matched"].asInt())
        assertEquals(0, dryRun(mapOf("channel" to channel, "feed" to "$base/elsewhere"))["matched"].asInt())
        assertEquals(0, dryRun(mapOf("channel" to channel, "unfollowed" to true))["matched"].asInt())
        // Spelling out dry_run: true is the same as leaving it out.
        assertEquals(3, json(bulk(mapOf("channel" to channel), dryRun = true).andExpect(status().isOk))["matched"].asInt())
        assertEquals(3, liveRows(channel))
    }

    @Test
    fun `bulk delete refuses vague, malformed and unknown scopes`() {
        fun bad(
            scope: Map<String, Any?>?,
            fragment: String,
        ) = bulk(scope)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString(fragment)))

        bad(null, "scope")
        bad(emptyMap(), "Name a channel")
        bad(mapOf("channel" to "global"), "Name a channel")
        bad(mapOf("state" to "all"), "Name a channel")
        bad(mapOf("channel" to "home", "state" to "unread"), "state")
        bad(mapOf("channel" to "home", "published_before" to "last week"), "published_before")
        bulk(mapOf("channel" to "no-such-channel"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
        // Narrow enough without a channel.
        bulk(mapOf("published_before" to "2000-01-01T00:00:00Z")).andExpect(status().isOk)
        bulk(mapOf("channel" to "global", "unfollowed" to true)).andExpect(status().isOk)
    }

    @Test
    fun `executing without the dry run's count is a 400 and deletes nothing`() {
        pages["/noexp"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        val channel = newChannel()
        follow(channel, "/noexp")

        bulk(mapOf("channel" to channel), dryRun = false)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString("expected")))
        execute(mapOf("channel" to channel), expected = -1).andExpect(status().isBadRequest)
        assertEquals(2, liveRows(channel))
    }

    @Test
    fun `executing is refused with 409 when more entries match than were expected`() {
        pages["/grow"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        val channel = newChannel()
        follow(channel, "/grow")
        val shown = dryRun(mapOf("channel" to channel))["matched"].asInt()

        // A fetch stores another entry between the dry run and the execution.
        pages["/grow"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        refresh.refreshClaimed(feedId(channel))
        jdbc.sql("update microsub_entries set is_read = true where url like '%/p3'").update() // new entries arrive unread

        execute(mapOf("channel" to channel), expected = shown)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("scope_changed"))
            .andExpect(jsonPath("$.matched").value(3))
            .andExpect(jsonPath("$.expected").value(2))
        assertEquals(3, liveRows(channel), "nothing was deleted")

        // Quoting the new count goes through. Fewer than expected is fine too.
        execute(mapOf("channel" to channel), expected = 10)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(3))
            .andExpect(jsonPath("$.remaining").value(0))
    }

    @Test
    fun `bulk delete marks entries a feed lists, so they stay deleted, and re-running it is a no-op`() {
        pages["/mark"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/mark")
        val scope = mapOf("channel" to channel, "published_before" to "2026-11-03T00:00:00Z")

        execute(scope, expected = 2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(2))
            .andExpect(jsonPath("$.remaining").value(0))
        assertEquals(
            listOf(
                "$base/p3",
            ),
            json(mockMvc.perform(get("/microsub").auth().param("action", "timeline").param("channel", channel)))["items"].toList().map {
                it["url"].asString()
            },
        )
        assertEquals(3, rows(channel), "the deleted entries stay as markers while their feed lists them")
        assertEquals(2, json(mockMvc.perform(get("/reader/v1/channels/$channel/deleted").auth()))["items"].size())

        // The same call again finds nothing left, however often it is repeated.
        repeat(2) {
            execute(scope, expected = 2)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.remaining").value(0))
        }
        assertEquals(0, dryRun(scope)["matched"].asInt())

        // A fetch that still lists them does not bring them back; one that no longer does drops the markers.
        refresh.refreshClaimed(feedId(channel))
        assertEquals(1, liveRows(channel))
        pages["/mark"] = "text/html" to hFeed(hEntry("p3", "Three"))
        refresh.refreshClaimed(feedId(channel))
        assertEquals(1, rows(channel))
    }

    @Test
    fun `bulk delete removes the entries of unfollowed feeds for good`() {
        pages["/gone"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"))
        pages["/kept"] = "text/html" to hFeed(hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/gone")
        follow(channel, "/kept")
        mockMvc
            .perform(
                post("/microsub")
                    .auth("full")
                    .param("action", "unfollow")
                    .param("channel", channel)
                    .param("url", "$base/gone"),
            ).andExpect(status().isOk)
        assertEquals(3, rows(channel), "unfollowing leaves the entries behind")

        val preview = dryRun(mapOf("channel" to channel, "unfollowed" to true))
        assertEquals(2, preview["matched"].asInt())
        assertEquals(2, preview["permanent"].asInt())
        execute(mapOf("channel" to channel, "unfollowed" to true), expected = 2)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(2))
        assertEquals(1, rows(channel), "the entries of the unfollowed feed are gone, not marked")
        assertEquals(
            emptyList<String>(),
            json(mockMvc.perform(get("/reader/v1/channels/$channel/deleted").auth()))["items"].toList().map {
                it.toString()
            },
        )
    }

    @Test
    fun `unread entries are only deleted with state all and an explicit confirmation`() {
        pages["/unr"] = "text/html" to hFeed(hEntry("p1", "One"), hEntry("p2", "Two"), hEntry("p3", "Three"))
        val channel = newChannel()
        follow(channel, "/unr")
        jdbc.sql("update microsub_entries set is_read = false where url like '%/p3'").update()
        assertEquals(1, unread(channel))

        // Read entries only by default: the unread one is neither counted nor deleted.
        val readOnly = mapOf("channel" to channel)
        assertEquals(2, dryRun(readOnly)["matched"].asInt())
        execute(readOnly, expected = 2).andExpect(status().isOk).andExpect(jsonPath("$.deleted").value(2))
        assertEquals(1, unread(channel))

        val everything = mapOf("channel" to channel, "state" to "all")
        val preview = dryRun(everything)
        assertEquals(1, preview["matched"].asInt())
        assertEquals(1, preview["unread"].asInt())
        // The second confirmation is part of the request, not the dry run.
        execute(everything, expected = 1)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString("confirm_unread")))
        execute(everything, expected = 1, confirmUnread = false).andExpect(status().isBadRequest)
        assertEquals(1, unread(channel))

        execute(everything, expected = 1, confirmUnread = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(1))
            .andExpect(jsonPath("$.remaining").value(0))
        assertEquals(0, unread(channel))
        assertEquals(0, liveRows(channel))
    }

    @Test
    fun `a large delete runs in chunks of 5000 that the client loops until none remain`() {
        val channel = newChannel()
        val total = 12_000
        // Entries a feed no longer owns, so the chunks hard-delete them.
        jdbc
            .sql(
                """
                insert into microsub_entries (id, channel_id, feed_id, source_key, published, data, is_read, created_at, updated_at, last_seen_at)
                select gen_random_uuid(), c.id, null, 'feed:https://old.example/feed:' || n, now() - interval '400 days', '{}'::jsonb, true, now(), now(), now()
                from microsub_channels c, generate_series(1, :total) n where c.uid = :uid
                """.trimIndent(),
            ).param("total", total)
            .param("uid", channel)
            .update()
        val scope = mapOf("channel" to channel, "unfollowed" to true)
        val expected = dryRun(scope)["matched"].asInt()
        assertEquals(total, expected)

        val deleted = mutableListOf<Int>()
        var remaining = expected
        while (remaining > 0) {
            val chunk = json(execute(scope, expected).andExpect(status().isOk))
            deleted += chunk["deleted"].asInt()
            assertTrue(chunk["deleted"].asInt() > 0, "every call makes progress")
            remaining = chunk["remaining"].asInt()
        }
        assertEquals(listOf(5000, 5000, 2000), deleted)
        assertEquals(0, rows(channel))
    }

    @Test
    fun `only one bulk delete runs at a time`() {
        pages["/busy"] = "text/html" to hFeed(hEntry("p1", "One"))
        val channel = newChannel()
        follow(channel, "/busy")
        val scope = mapOf("channel" to channel)

        dataSource.connection.use { held ->
            held.createStatement().use { it.execute("select pg_advisory_lock(7424117001)") }
            try {
                execute(scope, expected = 1)
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.error").value("busy"))
                assertEquals(1, liveRows(channel))
            } finally {
                held.createStatement().use { it.execute("select pg_advisory_unlock(7424117001)") }
            }
        }
        execute(scope, expected = 1).andExpect(status().isOk).andExpect(jsonPath("$.deleted").value(1))
    }

    @Test
    fun `the Microsub feed objects carry no reader details and the legacy routes are gone`() {
        pages["/legacy"] = "text/html" to hFeed(hEntry("p1", "First"))
        val channel = newChannel()
        follow(channel, "/legacy")

        val microsub =
            json(mockMvc.perform(get("/microsub").auth().param("action", "follow").param("channel", channel)).andExpect(status().isOk))
        val reader = json(mockMvc.perform(get("/reader/v1/channels/$channel/feeds").auth()).andExpect(status().isOk))
        assertEquals("mf2", reader["items"][0]["format"].asString())
        assertEquals(microsub["items"][0]["name"], reader["items"][0]["name"])
        assertFalse(microsub["items"][0].has("format"))
        assertFalse(microsub["items"][0].has("websub"))

        mockMvc.perform(post("/microsub/refresh").auth().param("channel", channel)).andExpect { assertTrue(it.response.status in 400..405) }
        // The Microsub chain still authenticates its own routes; the reader's Bearer-only rule does not leak into it.
        mockMvc.perform(get("/microsub").param("action", "channels")).andExpect(status().isUnauthorized)
    }
}
