package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.microformats2.Mf2ParseResult
import dev.jacobandersen.microformats2.Mf2Parser
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

sealed interface FeedLoad {
    data class Loaded(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
    ) : FeedLoad

    data object NotModified : FeedLoad

    data class Failed(
        val reason: String,
    ) : FeedLoad
}

/** A feed found on a page, for `action=search`. */
data class FeedCandidate(
    val url: String,
    val name: String?,
    val photo: String?,
    val description: String?,
)

/**
 * Turns a URL into a feed. Prefers microformats2 (an `h-feed` or `h-entry`
 * list on the page), then the page's `rel=alternate` Atom, RSS or JSON Feed,
 * as the spec recommends. Feed documents fetched directly are parsed by
 * content type, falling back to sniffing the body.
 */
@Component
class FeedDiscovery(
    private val fetcher: FeedFetcher,
    private val mf2Parser: Mf2Parser,
    private val objectMapper: ObjectMapper,
    private val config: MicrosubConfig,
) {
    fun load(
        url: String,
        etag: String?,
        lastModified: String?,
    ): FeedLoad = load(url, etag, lastModified, followAlternates = true)

    private fun load(
        url: String,
        etag: String?,
        lastModified: String?,
        followAlternates: Boolean,
    ): FeedLoad {
        val fetch = fetcher.fetch(url, etag, lastModified)
        if (fetch.notModified) return FeedLoad.NotModified
        if (!fetch.ok) return FeedLoad.Failed(fetch.error ?: "HTTP ${fetch.status}")

        val headerHubs = fetch.links.filter { it.rel == "hub" }.map { it.url }
        val headerSelf = fetch.links.firstOrNull { it.rel == "self" }?.url

        val parsed: ParsedFeed =
            when (kindOf(fetch)) {
                Kind.JSON -> {
                    JsonFeedParser.parse(objectMapper, fetch.body, fetch.finalUrl, config.maxItemsPerFetch)
                        ?: return FeedLoad.Failed("JSON document is not a JSON Feed")
                }

                Kind.XML -> {
                    runCatching { XmlFeedParser.parse(fetch.body, fetch.finalUrl, config.maxItemsPerFetch) }
                        .getOrElse { return FeedLoad.Failed("unreadable RSS/Atom feed: ${it.message}") }
                }

                Kind.HTML -> {
                    val html = fetch.body.toString(Charsets.UTF_8)
                    val mf2 = mf2Parser.parse(html, fetch.finalUrl)
                    if (Mf2FeedParser.hasEntries(mf2)) {
                        Mf2FeedParser
                            .parse(
                                mf2,
                                fetch.finalUrl,
                                titleOf(html),
                            ).let { it.copy(items = it.items.take(config.maxItemsPerFetch)) }
                    } else {
                        val alternate = alternates(mf2, fetch).firstOrNull()
                        if (!followAlternates || alternate == null) return FeedLoad.Failed("no feed found at ${fetch.finalUrl}")
                        return load(alternate.url, null, null, followAlternates = false)
                    }
                }
            }

        return FeedLoad.Loaded(
            feed =
                parsed.copy(
                    hubs = (headerHubs + parsed.hubs).distinct(),
                    self = headerSelf ?: parsed.self,
                ),
            etag = fetch.etag,
            lastModified = fetch.lastModified,
        )
    }

    /**
     * Feeds reachable from [url] for feed search: the page itself when it is
     * a feed or carries mf2 entries, plus its `rel=alternate` feeds.
     */
    fun candidates(url: String): List<FeedCandidate> {
        val fetch = fetcher.fetch(url, null, null)
        if (!fetch.ok) return emptyList()

        return when (kindOf(fetch)) {
            Kind.HTML -> {
                val html = fetch.body.toString(Charsets.UTF_8)
                val mf2 = mf2Parser.parse(html, fetch.finalUrl)
                val self =
                    if (Mf2FeedParser.hasEntries(mf2)) {
                        val feed = Mf2FeedParser.parse(mf2, fetch.finalUrl, titleOf(html))
                        listOf(FeedCandidate(fetch.finalUrl, feed.name, feed.photo, feed.description))
                    } else {
                        emptyList()
                    }
                val title = titleOf(html)
                self + alternates(mf2, fetch).map { FeedCandidate(it.url, it.title ?: title, null, null) }
            }

            else -> {
                when (val loaded = load(url, null, null, followAlternates = false)) {
                    is FeedLoad.Loaded -> {
                        listOf(
                            FeedCandidate(loaded.feed.url, loaded.feed.name, loaded.feed.photo, loaded.feed.description),
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        }.distinctBy { MicrosubUrls.normalize(it.url) }
    }

    private data class Alternate(
        val url: String,
        val title: String?,
    )

    private fun alternates(
        mf2: Mf2ParseResult,
        fetch: FetchResult,
    ): List<Alternate> {
        val fromHtml =
            mf2.relUrls
                .filter { (_, rel) -> "alternate" in rel.rels && rel.type?.lowercase()?.let(::isFeedType) == true }
                .map { (href, rel) -> Alternate(href, rel.title) }
        val fromHeaders =
            fetch.links
                .filter {
                    it.rel == "alternate" && it.type?.lowercase()?.let(::isFeedType) == true
                }.map { Alternate(it.url, null) }
        return (fromHtml + fromHeaders).sortedBy { feedPreference(it.url, mf2) }
    }

    private fun feedPreference(
        url: String,
        mf2: Mf2ParseResult,
    ): Int {
        val type =
            mf2.relUrls[url]
                ?.type
                ?.lowercase()
                .orEmpty()
        return when {
            type.contains("mf2") || type.contains("jf2") -> 0
            type.contains("atom") -> 1
            type.contains("rss") -> 2
            else -> 3
        }
    }

    private fun isFeedType(type: String): Boolean =
        type.contains("rss") || type.contains("atom") || type.contains("feed+json") || type.contains("jf2feed") || type.contains("mf2")

    private enum class Kind { HTML, XML, JSON }

    private fun kindOf(fetch: FetchResult): Kind {
        val type = fetch.contentType?.lowercase().orEmpty()
        return when {
            type.contains("json") -> Kind.JSON
            type.contains("rss") || type.contains("atom") || (type.contains("xml") && !type.contains("xhtml")) -> Kind.XML
            type.contains("html") -> Kind.HTML
            else -> sniff(fetch.body)
        }
    }

    private fun sniff(body: ByteArray): Kind {
        val head =
            body
                .copyOfRange(
                    0,
                    minOf(body.size, SNIFF_BYTES),
                ).toString(Charsets.UTF_8)
                .trimStart(BOM, ' ', '\n', '\r', '\t')
                .lowercase()
        return when {
            head.startsWith("{") -> Kind.JSON
            head.startsWith("<?xml") && !head.contains("<html") -> Kind.XML
            head.startsWith("<rss") || head.startsWith("<feed") || head.startsWith("<rdf") -> Kind.XML
            else -> Kind.HTML
        }
    }

    private fun titleOf(html: String): String? =
        Jsoup
            .parse(html)
            .title()
            .trim()
            .takeIf { it.isNotEmpty() }

    companion object {
        private const val SNIFF_BYTES = 512
        private const val BOM = '\uFEFF'
    }
}
