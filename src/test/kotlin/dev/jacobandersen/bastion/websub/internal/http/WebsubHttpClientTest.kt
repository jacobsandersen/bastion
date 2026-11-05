package dev.jacobandersen.bastion.websub.internal.http

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

class WebsubHttpClientTest {
    private val client = WebsubHttpClient(WebsubConfig())

    @Test
    fun `publishes form-encoded hub mode and topic payload`() {
        var method: String? = null
        var contentType: String? = null
        var body: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            method = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = client.publish("http://127.0.0.1:${server.address.port}/hub", "https://bastion.test/feed.xml")

            assertInstanceOf(PublishResult.Success::class.java, result)
            assertEquals("POST", method)
            assertTrue(contentType!!.startsWith("application/x-www-form-urlencoded"))
            val params = parseForm(body!!)
            assertEquals("publish", params["hub.mode"])
            assertEquals("https://bastion.test/feed.xml", params["hub.url"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sends subscription requests with callback, secret and lease`() {
        var body: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
        try {
            val result =
                client.subscription(
                    hubUrl = "http://127.0.0.1:${server.address.port}/hub",
                    mode = "subscribe",
                    topicUrl = "https://feeds.example/feed",
                    callbackUrl = "https://bastion.test/websub/callback/abc",
                    secret = "s3cret",
                    leaseSeconds = 3600,
                )

            assertInstanceOf(PublishResult.Success::class.java, result)
            val params = parseForm(body!!)
            assertEquals("subscribe", params["hub.mode"])
            assertEquals("https://feeds.example/feed", params["hub.topic"])
            assertEquals("https://bastion.test/websub/callback/abc", params["hub.callback"])
            assertEquals("s3cret", params["hub.secret"])
            assertEquals("3600", params["hub.lease_seconds"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `flags transient statuses as retryable failures`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = client.publish("http://127.0.0.1:${server.address.port}/hub", "https://bastion.test/feed.xml")

            assertInstanceOf(PublishResult.Failure::class.java, result)
            val failure = result as PublishResult.Failure
            assertEquals(503, failure.statusCode)
            assertTrue(failure.retryable)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `flags permanent statuses as non-retryable failures`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = client.publish("http://127.0.0.1:${server.address.port}/hub", "https://bastion.test/feed.xml")

            assertInstanceOf(PublishResult.Failure::class.java, result)
            val failure = result as PublishResult.Failure
            assertEquals(400, failure.statusCode)
            assertFalse(failure.retryable)
        } finally {
            server.stop(0)
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body
            .split("&")
            .associate { part ->
                val (key, value) = part.split("=", limit = 2)
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
}
