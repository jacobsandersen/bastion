package dev.jacobandersen.bastion.microsub.internal.fetch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedFetcherTest {
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
