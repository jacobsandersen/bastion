package dev.jacobandersen.bastion.microsub.internal.feed

import dev.jacobandersen.bastion.microsub.internal.fetch.FeedDiscovery
import dev.jacobandersen.bastion.microsub.internal.fetch.FeedLoad
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.stereotype.Service

/**
 * Feed search (`action=search` without a channel) and `action=preview`.
 * Neither stores anything, and both fetch anonymously, so a previewed site
 * cannot tell who is looking. Search only inspects the URL the user typed
 * and never reveals other followed feeds.
 */
@Service
class DiscoveryService(
    private val discovery: FeedDiscovery,
) {
    fun searchFeeds(query: String?): Map<String, Any> {
        val text = query?.trim()?.takeIf { it.isNotEmpty() } ?: throw MicrosubError.invalidRequest("The 'query' parameter is required")
        val url = MicrosubUrls.fromUserInput(text) ?: return mapOf("results" to emptyList<Any>())
        val results =
            discovery.candidates(url).map { candidate ->
                jf2 {
                    this["type"] = "feed"
                    this["url"] = candidate.url
                    this["name"] = candidate.name
                    this["photo"] = candidate.photo
                    this["description"] = candidate.description
                }
            }
        return mapOf("results" to results)
    }

    fun preview(url: String?): Map<String, Any> {
        val target = url?.let(MicrosubUrls::fromUserInput) ?: throw MicrosubError.invalidRequest("The 'url' parameter must be a URL")
        return when (val load = discovery.load(target, null, null)) {
            is FeedLoad.Loaded -> {
                mapOf(
                    "feed" to
                        jf2 {
                            this["type"] = "feed"
                            this["url"] = target
                            this["name"] = load.feed.name
                            this["photo"] = load.feed.photo
                            this["description"] = load.feed.description
                        },
                    "items" to load.feed.items.map { it.data },
                )
            }

            is FeedLoad.Failed -> {
                throw MicrosubError.invalidRequest("Could not load a feed from $target: ${load.reason}")
            }

            FeedLoad.NotModified -> {
                throw MicrosubError.invalidRequest("Could not load a feed from $target")
            }
        }
    }
}
