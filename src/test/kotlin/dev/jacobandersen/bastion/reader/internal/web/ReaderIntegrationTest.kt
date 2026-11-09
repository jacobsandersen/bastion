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

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
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
            .andExpect(jsonPath("$.features", hasSize<Any>(3)))
            .andExpect(jsonPath("$.features", hasItems("refresh", "events", "feed-details")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
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
                { get("/reader/v1/events").accept(MediaType.TEXT_EVENT_STREAM) },
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

    // --- the legacy Microsub extras are untouched --------------------------------------------------------------------

    @Test
    fun `the legacy Microsub routes and feed fields still work and agree with the reader API`() {
        pages["/legacy"] = "text/html" to hFeed(hEntry("p1", "First"))
        val channel = newChannel()
        follow(channel, "/legacy")

        val legacy =
            json(mockMvc.perform(get("/microsub").auth().param("action", "follow").param("channel", channel)).andExpect(status().isOk))
        val reader = json(mockMvc.perform(get("/reader/v1/channels/$channel/feeds").auth()).andExpect(status().isOk))
        assertEquals("mf2", legacy["items"][0]["format"].asString())
        assertEquals(legacy["items"][0]["format"], reader["items"][0]["format"])
        assertEquals(legacy["items"][0]["name"], reader["items"][0]["name"])

        mockMvc
            .perform(post("/microsub/refresh").auth().param("channel", channel))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.refreshing").value(0))
        mockMvc
            .perform(post("/microsub").auth().param("action", "refresh").param("channel", channel))
            .andExpect(status().isBadRequest) // still not an action of the Microsub endpoint
        // The Microsub chain still authenticates its own routes; the reader's Bearer-only rule does not leak into it.
        mockMvc.perform(get("/microsub").param("action", "channels")).andExpect(status().isUnauthorized)
    }
}
