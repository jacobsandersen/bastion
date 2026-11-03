package dev.jacobandersen.bastion

import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.read.PostFeedQuery
import dev.jacobandersen.bastion.post.read.PostReadService
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostTagFilter
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
@Transactional
class UntaggedPostLookupIntegrationTest {
    @Autowired
    lateinit var postService: PostService

    @Autowired
    lateinit var postReadService: PostReadService

    @Test
    fun `none finds only public published untagged posts and combines with ordinary tags`() {
        val prefix = "untagged-lookup-${System.nanoTime()}"
        val publicUntagged = create(prefix + "-public-untagged", PostStatus.PUBLISHED, PostVisibility.PUBLIC, false)
        val publicBlankCategories = create(prefix + "-public-blank", PostStatus.PUBLISHED, PostVisibility.PUBLIC, false, listOf(" ", ""))
        val publicKotlin = create(prefix + "-public-kotlin", PostStatus.PUBLISHED, PostVisibility.PUBLIC, false, listOf("Kotlin", "java"))
        val publicJava = create(prefix + "-public-java", PostStatus.PUBLISHED, PostVisibility.PUBLIC, false, listOf("JAVA"))
        create(prefix + "-unlisted", PostStatus.PUBLISHED, PostVisibility.UNLISTED, false)
        create(prefix + "-private", PostStatus.PUBLISHED, PostVisibility.PRIVATE, false)
        create(prefix + "-draft", PostStatus.DRAFT, PostVisibility.PUBLIC, false)
        create(prefix + "-deleted", PostStatus.PUBLISHED, PostVisibility.PUBLIC, true)

        val untagged =
            postReadService.feed(
                PostFeedQuery(
                    tag = listOf("none"),
                    limit = 100,
                ),
            )
        assertEquals(setOf(publicUntagged.slug, publicBlankCategories.slug), untagged.posts.map { it.slug }.toSet())

        val untaggedOrKotlin =
            postReadService.feed(
                PostFeedQuery(
                    tag = listOf("none", "KOTLIN"),
                    limit = 100,
                ),
            )
        assertEquals(
            setOf(publicUntagged.slug, publicBlankCategories.slug, publicKotlin.slug),
            untaggedOrKotlin.posts.map { it.slug }.toSet(),
        )

        val kotlinOnly =
            postReadService.feed(
                PostFeedQuery(
                    tag = listOf("kotlin"),
                    limit = 100,
                ),
            )
        assertEquals(listOf(publicKotlin.slug), kotlinOnly.posts.map { it.slug })

        val allPublic =
            postReadService.feed(
                PostFeedQuery(limit = 100),
            )
        assertTrue(allPublic.posts.map { it.slug }.containsAll(listOf(publicUntagged.slug, publicKotlin.slug, publicJava.slug)))
        assertFalse(allPublic.posts.map { it.slug }.contains(prefix + "-private"))

        val tags = postReadService.tags(limitArg = 100, offsetArg = 0)
        assertFalse(tags.tags.any { it.tag == PostTagFilter.UNTAGGED })
    }

    private fun create(
        slug: String,
        status: PostStatus,
        visibility: PostVisibility,
        deleted: Boolean,
        categories: List<String> = emptyList(),
    ) = postService.create(
        slug = slug,
        status = status,
        visibility = visibility,
        deleted = deleted,
        post =
            Mf2Object(
                type = listOf("h-entry"),
                properties =
                    buildMap {
                        put("content", listOf(Mf2Value.String("content for $slug")))
                        if (categories.isNotEmpty()) {
                            put("category", categories.map { Mf2Value.String(it) })
                        }
                    },
            ),
    )
}
