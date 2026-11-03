package dev.jacobandersen.bastion.post.internal.types

import dev.jacobandersen.bastion.post.internal.entity.PostEntity
import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.util.UUID

class PostTypeReclassificationServiceTest {
    private val fingerprint = "fingerprint"
    private val catalog = mock<PostTypeCatalog>()
    private val stateService = mock<PostTypeStateService>()
    private val repository = mock<PostRepository>()
    private val writer = mock<PostTypeBackfillWriter>()

    private fun service(
        dryRun: Boolean = false,
        batchSize: Int = 10,
    ) = PostTypeReclassificationService(
        catalog.also {
            whenever(it.backfill).thenReturn(PostTypesConfig.Backfill(batchSize = batchSize, dryRun = dryRun))
        },
        stateService,
        repository,
        writer,
    )

    private fun entity(
        type: String?,
        discovered: String?,
    ): PostEntity {
        val entity =
            PostEntity(
                slug = "post-${UUID.randomUUID()}",
                status = PostStatus.PUBLISHED,
                visibility = PostVisibility.PUBLIC,
                post =
                    Mf2Object(
                        type = listOf("h-entry"),
                        properties = mapOf("content" to listOf(Mf2Value.String("hello $type ${UUID.randomUUID()}"))),
                    ),
                type = type,
            )
        entity.id = UUID.randomUUID()
        whenever(catalog.discover(entity.post)).thenReturn(PostTypeDiscovery(discovered, "rule"))
        return entity
    }

    @Test
    fun `updates only rows whose discovered type changed`() {
        val unchanged = entity("note", "note")
        val changed = entity("note", "article")
        whenever(catalog.fingerprint).thenReturn(fingerprint)
        whenever(stateService.tryClaim(any(), any())).thenReturn(fingerprint)
        whenever(repository.findAllByOrderByIdAsc(any<Pageable>())).thenReturn(listOf(unchanged, changed))

        service().reconcile()

        val captor = argumentCaptor<List<PostTypeChange>>()
        verify(writer).updateTypes(captor.capture())
        assertEquals(listOf(PostTypeChange(requireNotNull(changed.id), "note", "article")), captor.firstValue)
        verify(stateService).complete(eq(fingerprint), eq(2L), eq(1L), any())
    }

    @Test
    fun `dry run reports changes without writing them`() {
        val changed = entity("note", "article")
        whenever(catalog.fingerprint).thenReturn(fingerprint)
        whenever(stateService.tryClaim(any(), any())).thenReturn(fingerprint)
        whenever(repository.findAllByOrderByIdAsc(any<Pageable>())).thenReturn(listOf(changed))

        service(dryRun = true).reconcile()

        verify(writer, never()).updateTypes(any())
        verify(stateService, never()).complete(any(), any(), any(), any())
        verify(stateService).release()
    }

    @Test
    fun `does nothing when the ruleset is already applied or claimed`() {
        whenever(stateService.tryClaim(any(), any())).thenReturn(null)

        service().reconcile()

        verify(repository, never()).findAllByOrderByIdAsc(any<Pageable>())
        verify(writer, never()).updateTypes(any())
    }

    @Test
    fun `releases the claim when the local ruleset differs`() {
        whenever(catalog.fingerprint).thenReturn(fingerprint)
        whenever(stateService.tryClaim(any(), any())).thenReturn("other")

        service().reconcile()

        verify(stateService).release()
        verify(repository, never()).findAllByOrderByIdAsc(any<Pageable>())
    }
}
