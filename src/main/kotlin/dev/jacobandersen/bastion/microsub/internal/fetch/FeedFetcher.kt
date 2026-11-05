package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.util.HttpUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val logger = KotlinLogging.logger {}

data class LinkRel(
    val rel: String,
    val url: String,
    val type: String?,
)

data class FetchResult(
    val status: Int,
    val finalUrl: String,
    val contentType: String?,
    val body: ByteArray,
    val etag: String?,
    val lastModified: String?,
    val links: List<LinkRel>,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && status in 200..299
    val notModified: Boolean get() = error == null && status == 304

    companion object {
        fun failed(
            url: String,
            reason: String,
        ) = FetchResult(0, url, null, ByteArray(0), null, null, emptyList(), reason)
    }
}

/** Seam over the host check so tests can fetch from a local server. */
fun interface FeedHostValidator {
    fun isBlocked(url: String): Boolean
}

@Component
class DefaultFeedHostValidator : FeedHostValidator {
    override fun isBlocked(url: String): Boolean = HttpUtil.isBlockedHost(url, failClosedOnDnsError = true)
}

/**
 * Fetches untrusted feed URLs. Redirects are followed by hand so every hop is
 * checked against private and loopback addresses (SSRF), bodies are capped,
 * and requests carry no user-identifying data, which also keeps `preview`
 * anonymous as the spec asks.
 */
@Component
class FeedFetcher(
    private val config: MicrosubConfig,
    private val hostValidator: FeedHostValidator,
) {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun fetch(
        url: String,
        etag: String?,
        lastModified: String?,
    ): FetchResult =
        runCatching { fetchFollowingRedirects(url, etag, lastModified) }
            .getOrElse { error ->
                logger.warn { "Fetching feed $url failed: ${error.message}" }
                FetchResult.failed(url, error.message ?: error::class.simpleName ?: "fetch failed")
            }

    private fun fetchFollowingRedirects(
        startUrl: String,
        etag: String?,
        lastModified: String?,
    ): FetchResult {
        var current = startUrl
        repeat(MAX_REDIRECTS + 1) {
            if (hostValidator.isBlocked(current)) return FetchResult.failed(current, "host is not allowed")

            val request =
                HttpRequest
                    .newBuilder(URI(current))
                    .timeout(config.readTimeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT)
                    .apply {
                        if (current == startUrl) {
                            etag?.let { header("If-None-Match", it) }
                            lastModified?.let { header("If-Modified-Since", it) }
                        }
                    }.GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            if (status in REDIRECTS) {
                response.body().close()
                val location = response.headers().firstValue("Location").orElse(null)
                current =
                    MicrosubUrls.resolve(current, location) ?: return FetchResult.failed(current, "redirect without a usable Location")
                return@repeat
            }

            val body =
                response.body().use { stream ->
                    stream.readNBytes(config.maxBodyBytes + 1)
                }
            if (body.size > config.maxBodyBytes) return FetchResult.failed(current, "feed exceeds ${config.maxBodyBytes} bytes")

            val headers = response.headers()
            return FetchResult(
                status = status,
                finalUrl = current,
                contentType = headers.firstValue("Content-Type").orElse(null),
                body = body,
                etag = headers.firstValue("ETag").orElse(null),
                lastModified = headers.firstValue("Last-Modified").orElse(null),
                links = headers.allValues("Link").flatMap { parseLinkHeader(it, current) },
            )
        }
        return FetchResult.failed(startUrl, "too many redirects")
    }

    companion object {
        const val USER_AGENT = "Bastion-Microsub/1.0 (+https://indieweb.org/Microsub)"
        private const val ACCEPT =
            "application/feed+json, application/atom+xml, application/rss+xml, application/xml;q=0.9, " +
                "text/html;q=0.9, application/json;q=0.8, */*;q=0.5"
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Parses an RFC 8288 `Link` header value into its relations, resolving each target against [base]. */
        fun parseLinkHeader(
            value: String,
            base: String,
        ): List<LinkRel> =
            Regex("<([^>]*)>([^,<]*)")
                .findAll(value)
                .flatMap { match ->
                    val target = MicrosubUrls.resolve(base, match.groupValues[1]) ?: return@flatMap emptySequence()
                    val params =
                        match.groupValues[2]
                            .split(';')
                            .mapNotNull { param ->
                                val (key, raw) = param.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                                key.trim().lowercase() to raw.trim().trim('"')
                            }.toMap()
                    val type = params["type"]
                    params["rel"]
                        .orEmpty()
                        .split(Regex("\\s+"))
                        .filter(String::isNotBlank)
                        .map { LinkRel(it.lowercase(), target, type) }
                        .asSequence()
                }.toList()
    }
}
