package dev.jacobandersen.bastion.post.internal.entity

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.post.internal.entity.PostEntity
import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PostEntityJsonRoundTripTest {
    @Autowired
    lateinit var repository: PostRepository

    @Test
    fun persistsAndReloadsMf2Object() {
        val slug = "json-roundtrip-${System.nanoTime()}"
        val obj =
            Mf2Object(
                type = listOf("h-entry"),
                properties =
                    mutableMapOf(
                        "name" to listOf(Mf2Value.String("hello world")),
                        "published" to listOf(Mf2Value.String("2026-08-31T00:00:00Z")),
                        "updated" to listOf(Mf2Value.String("2026-08-31T00:00:00Z")),
                        "draft" to listOf(Mf2Value.Boolean(false)),
                        "count" to listOf(Mf2Value.Number(42)),
                        "rating" to listOf(Mf2Value.Double(4.5)),
                        "author" to
                            listOf(
                                Mf2Value.Object(
                                    Mf2Object(
                                        type = listOf("h-card"),
                                        properties = mutableMapOf("name" to listOf(Mf2Value.String("Jacob"))),
                                        children = null,
                                    ),
                                ),
                            ),
                    ),
                children = null,
            )

        val saved = repository.saveAndFlush(PostEntity(slug, PostStatus.PUBLISHED, PostVisibility.PUBLIC, false, obj, type = "note"))
        val reloaded = repository.findById(saved.id!!).orElseThrow()

        assertEquals(slug, reloaded.slug)
        assertEquals(obj, reloaded.post)

        assertEquals("h-entry", saved.h)
        assertEquals("h-entry", reloaded.h)
        assertEquals("note", reloaded.type)
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), reloaded.createdAtUtc)
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), reloaded.updatedAtUtc)
    }
}
