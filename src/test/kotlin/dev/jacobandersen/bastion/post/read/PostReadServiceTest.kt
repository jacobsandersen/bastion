package dev.jacobandersen.bastion.post.read

import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.internal.syndication.PostSyndicationService
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import dev.jacobandersen.bastion.post.internal.types.PostTypesConfig
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormatConfig
import dev.jacobandersen.bastion.post.types.PostMf2Type
import dev.jacobandersen.bastion.post.types.PostTagFilter
import dev.jacobandersen.bastion.url.ContentUrlConfig
import dev.jacobandersen.bastion.url.ContentUrlService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mock.env.MockEnvironment
import java.time.Instant
import java.time.ZoneId

class PostReadServiceTest {
    private val postService = mock<PostService>()
    private val postRepository = mock<PostRepository>()
    private val postSyndicationService = mock<PostSyndicationService>()
    private val urlService =
        ContentUrlService(
            ContentUrlConfig(
                baseUrl = "https://bastion.test",
                pathPattern = "{year}/{month}/{day}/{slug}",
            ),
        )
    private val registry =
        PostTypeCatalog(
            PostTypesConfig(
                roots = mapOf("entry" to PostTypesConfig.Root(fallback = "note")),
                defaults = PostTypesConfig.Defaults(SyndicationFormatConfig(format = "{excerpt}\n\n{url}")),
                types =
                    listOf(
                        PostTypesConfig.PostTypeDefinition(type = "note"),
                        PostTypesConfig.PostTypeDefinition(type = "article"),
                        PostTypesConfig.PostTypeDefinition(type = "reply"),
                        PostTypesConfig.PostTypeDefinition(type = "bookmark"),
                        PostTypesConfig.PostTypeDefinition(type = "mood"),
                    ),
            ),
            DefaultResourceLoader(),
            MockEnvironment().withProperty("bastion.post-types.roots.entry.fallback", "note"),
        )
    private val service =
        PostReadService(
            postService,
            postRepository,
            postSyndicationService,
            registry,
            urlService,
            ZoneId.systemDefault(),
        )

    private fun stubFeed() {
        whenever(
            postService.findFeedPosts(
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            ),
        ).thenReturn(emptyList())
    }

    private fun captureFeed(): Triple<
        List<PostMf2Type>?,
        List<String>?,
        PostTagFilter?,
    > {
        val mf2Captor = argumentCaptor<List<PostMf2Type>?>()
        val typeCaptor = argumentCaptor<List<String>?>()
        val tagCaptor = argumentCaptor<PostTagFilter?>()
        verify(postService).findFeedPosts(
            mf2Captor.capture(),
            typeCaptor.capture(),
            eq(10),
            eq(0),
            anyOrNull<Instant>(),
            anyOrNull<Instant>(),
            tagCaptor.capture(),
        )
        return Triple(mf2Captor.firstValue, typeCaptor.firstValue, tagCaptor.firstValue)
    }

    @Test
    fun `csv splits for h and type`() {
        stubFeed()

        service.feed(PostFeedQuery(h = listOf("entry,h-card"), type = listOf("note,article")))

        val (mf2Types, types, tag) = captureFeed()
        assertEquals(listOf(PostMf2Type.H_ENTRY, PostMf2Type.H_CARD), mf2Types)
        assertEquals(listOf("note", "article"), types)
        assertEquals(null, tag)
    }

    @Test
    fun `repeated and csv combined`() {
        stubFeed()

        service.feed(
            PostFeedQuery(
                h = listOf("entry", "h-card"),
                type = listOf("note,article", "reply"),
            ),
        )

        val (mf2Types, types, _) = captureFeed()
        assertEquals(listOf(PostMf2Type.H_ENTRY, PostMf2Type.H_CARD), mf2Types)
        assertEquals(listOf("note", "article", "reply"), types)
    }

    @Test
    fun `empty after split yields no filter`() {
        stubFeed()

        service.feed(PostFeedQuery(h = listOf(" , "), type = listOf("")))

        val (mf2Types, types, tag) = captureFeed()
        assertEquals(null, mf2Types)
        assertEquals(null, types)
        assertEquals(null, tag)
    }

    @Test
    fun `none is parsed as an untagged filter and can be combined with tags`() {
        stubFeed()

        service.feed(PostFeedQuery(tag = listOf(" NONE, Kotlin ", "JAVA", "kotlin")))

        val (_, _, tag) = captureFeed()
        assertEquals(
            PostTagFilter(tags = setOf("kotlin", "java"), includeUntagged = true),
            tag,
        )
    }

    @Test
    fun `unknown h type is rejected`() {
        assertThrows<IllegalArgumentException> {
            service.feed(PostFeedQuery(h = listOf("unknown-thing")))
        }
    }

    @Test
    fun `unknown post type is rejected`() {
        assertThrows<IllegalArgumentException> {
            service.feed(PostFeedQuery(type = listOf("unknown-thing")))
        }
    }
}
