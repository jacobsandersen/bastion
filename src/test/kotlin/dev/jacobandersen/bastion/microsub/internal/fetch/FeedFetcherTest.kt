package dev.jacobandersen.bastion.microsub.internal.fetch

import com.sun.net.httpserver.HttpServer
import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

class FeedFetcherTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/feed") { exchange ->
            val bytes = "<feed/>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/large") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(ByteArray(4096)) } }
        }
        server.createContext("/trickle") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { out ->
                    repeat(100) {
                        out.write('x'.code)
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun fetcher() =
        FeedFetcher(
            MicrosubConfig(readTimeout = Duration.ofSeconds(1), maxBodyBytes = 1024),
            FeedHostValidator { false },
        )

    @Test
    fun `reads a feed body`() {
        val result = fetcher().fetch("$base/feed", null, null)

        assertTrue(result.ok)
        assertArrayEquals("<feed/>".toByteArray(), result.body)
    }

    @Test
    fun `rejects bodies over the size cap`() {
        val result = fetcher().fetch("$base/large", null, null)

        assertFalse(result.ok)
        assertEquals("feed exceeds 1024 bytes", result.error)
    }

    @Test
    fun `gives up on a body that trickles past the read timeout`() {
        val started = System.nanoTime()
        val result = fetcher().fetch("$base/trickle", null, null)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertFalse(result.ok)
        assertTrue(elapsed < Duration.ofSeconds(5), "fetch took $elapsed, expected the 1s deadline to stop it")
    }

    @Test
    fun `parses Link headers with several relations`() {
        val links =
            FeedFetcher.parseLinkHeader(
                """<https://hub.example/>; rel="hub", </feed>; rel="self alternate"; type="application/atom+xml"""",
                "https://site.example/page",
            )

        assertEquals(
            listOf(
                LinkRel("hub", "https://hub.example/", null),
                LinkRel("self", "https://site.example/feed", "application/atom+xml"),
                LinkRel("alternate", "https://site.example/feed", "application/atom+xml"),
            ),
            links,
        )
    }
}
