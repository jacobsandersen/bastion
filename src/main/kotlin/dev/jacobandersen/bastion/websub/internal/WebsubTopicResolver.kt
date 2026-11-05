package dev.jacobandersen.bastion.websub.internal

import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.post.events.PostFeedFacets
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Works out which configured feed topics a post change touched.
 *
 * A feed is touched when the post was in it before the change or is in it
 * after, so a retagged, retyped or demoted post also pings the feeds it left.
 */
@Component
class WebsubTopicResolver(
    private val config: WebsubConfig,
) {
    fun resolve(
        event: PostChangedEvent,
        currentYear: Int,
    ): Set<String> {
        val topics = linkedSetOf<String>()
        if (event.wasFeedVisible) event.previousFacets?.let { topics += topicsFor(it, currentYear) }
        if (event.isFeedVisible) event.facets?.let { topics += topicsFor(it, currentYear) }
        return topics
    }

    private fun topicsFor(
        facets: PostFeedFacets,
        currentYear: Int,
    ): Set<String> {
        val t = config.topics
        val topics = linkedSetOf<String>()
        if (t.main.isNotBlank()) topics += t.main
        if (t.uncategorized.isNotBlank() && facets.tags.isEmpty()) topics += t.uncategorized
        if (t.type.isNotBlank() && facets.type != null && (t.typeNames.isEmpty() || facets.type in t.typeNames)) {
            topics += t.type.replace("{type}", encode(facets.type))
        }
        if (t.tag.isNotBlank()) facets.tags.forEach { topics += t.tag.replace("{tag}", encode(it)) }
        if (t.year.isNotBlank() && facets.publishedYear == currentYear) {
            topics += t.year.replace("{year}", currentYear.toString())
        }
        return topics
    }

    /** Matches JavaScript's `encodeURIComponent`, which is what the frontend uses to build feed URLs. */
    private fun encode(value: String): String =
        URLEncoder
            .encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
}
