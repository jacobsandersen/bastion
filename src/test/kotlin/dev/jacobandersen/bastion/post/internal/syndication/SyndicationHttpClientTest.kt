package dev.jacobandersen.bastion.post.internal.syndication

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.UUID

class SyndicationHttpClientTest {
    private val mapper: ObjectMapper = JsonMapper.builderWithJackson2Defaults().build()

    private val client = SyndicationHttpClient(mapper)

    private val noteFormat =
        SyndicationFormat.parse("{excerpt}\n\n{url}", null, "{url}", "test note")

    private val articleFormat =
        SyndicationFormat.parse(
            "{name}: {excerpt}\n\n{url}",
            "{excerpt}\n\n{url}",
            "{name}\n\n{url}",
            "test article",
        )

    private fun target(port: Int): SyndicationConfig.Target =
        SyndicationConfig.Target(
            uid = "target",
            name = "Target",
            endpoint = "http://127.0.0.1:$port/micropub",
        )

    private fun post(type: String? = null): Post =
        Post(
            id = UUID.randomUUID(),
            slug = "post",
            status = PostStatus.PUBLISHED,
            visibility = PostVisibility.PUBLIC,
            h = "h-entry",
            type = type,
            post =
                Mf2Object(
                    type = listOf("h-entry"),
                    properties =
                        mapOf(
                            "name" to listOf(Mf2Value.String("Title")),
                            "content" to listOf(Mf2Value.String("A long body that will be excerpted.")),
                            "summary" to listOf(Mf2Value.String("A long body that will be excerpted.")),
                        ),
                ),
        )

    @Test
    fun `sendUpdate posts a JSON action update body`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var capturedContentType: String? = null
        var capturedBody: String? = null
        server.createContext("/") { exchange ->
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val result =
                client.sendUpdate(
                    target(server.address.port),
                    "https://bastion.test/post/1",
                    SyndicationUpdate(
                        replace = """{"name":["Renamed"]}""",
                        add = """{"category":["extra"]}""",
                        delete = """["name"]""",
                    ),
                )
            assertTrue(result is SyndicationSendResult.Success)
        } finally {
            server.stop(0)
        }

        assertTrue(
            capturedContentType!!.startsWith("application/json"),
            "expected json content type, got $capturedContentType",
        )
        val body = mapper.readTree(capturedBody!!)
        assertEquals("update", body.get("action").asString())
        assertEquals("https://bastion.test/post/1", body.get("url").asString())
        assertEquals(
            "Renamed",
            body
                .get("replace")
                .get("name")
                .get(0)
                .asString(),
        )
        assertEquals(
            "extra",
            body
                .get("add")
                .get("category")
                .get(0)
                .asString(),
        )
        assertEquals("name", body.get("delete").get(0).asString())
    }

    @Test
    fun `sendDelete posts a form-encoded delete action`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var capturedContentType: String? = null
        var capturedBody: String? = null
        server.createContext("/") { exchange ->
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = client.sendDelete(target(server.address.port), "https://bastion.test/post/1")
            assertTrue(result is SyndicationSendResult.Success)
        } finally {
            server.stop(0)
        }

        assertTrue(capturedContentType!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("action=delete&url=https%3A%2F%2Fbastion.test%2Fpost%2F1", capturedBody)
    }

    @Test
    fun `serializeExcerptUpdate replaces excerpt url and article title`() {
        val update =
            client.serializeExcerptUpdate(
                post(type = "article"),
                "https://bastion.test/post/1",
                300,
                articleFormat,
            )

        val replace = mapper.readTree(update.replace!!)
        assertEquals(1, replace.get("content").size())
        assertEquals(1, replace.get("summary").size())
        assertEquals("https://bastion.test/post/1", replace.get("url").get(0).asString())
        assertEquals("Title", replace.get("name").get(0).asString())
    }

    @Test
    fun `serializeExcerptUpdate omits title for notes`() {
        val update =
            client.serializeExcerptUpdate(
                post(type = "note"),
                "https://bastion.test/post/1",
                300,
                noteFormat,
            )

        val replace = mapper.readTree(update.replace!!)
        assertTrue(!replace.has("name"))
    }
}
