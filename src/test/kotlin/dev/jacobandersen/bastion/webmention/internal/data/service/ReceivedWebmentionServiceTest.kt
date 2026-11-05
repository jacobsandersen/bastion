package dev.jacobandersen.bastion.webmention.internal.data.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.NestedResponse
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionAnalysis
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.entity.ReceivedWebmentionEntity
import dev.jacobandersen.bastion.webmention.internal.data.repository.ReceivedWebmentionRepository
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import java.time.Instant
import java.util.UUID

class ReceivedWebmentionServiceTest {
    private val repository = mock(ReceivedWebmentionRepository::class.java)
    private val service = ReceivedWebmentionService(repository)

    init {
        `when`(repository.save(any())).thenAnswer {
            val entity = it.arguments[0] as ReceivedWebmentionEntity
            if (entity.id == null) entity.id = UUID.randomUUID()
            entity
        }
    }

    private val sourceUrl = "https://source.example/reply"
    private val targetUrl = "https://bastion.test/2026/01/01/post"
    private val postId = UUID.randomUUID()

    private fun entity(
        state: ReceivedWebmentionState = ReceivedWebmentionState.PENDING,
        interaction: WebmentionInteraction? = null,
        contentText: List<String>? = null,
    ) = ReceivedWebmentionEntity(
        postId = postId,
        sourceUrl = sourceUrl,
        targetUrl = targetUrl,
        state = state,
        interaction = interaction,
        authorName = null,
        authorUrl = null,
        authorPhoto = null,
        contentText = contentText,
        contentHtml = null,
        rawMf2 = null,
        lastError = null,
        firstSeenAt = Instant.now(),
        verifiedAt = null,
        updatedAtUtc = Instant.now(),
    )

    @Test
    fun `ensurePending creates a new pending record`() {
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(null)

        service.ensurePending(sourceUrl, targetUrl, postId)

        val captor = ArgumentCaptor.forClass(ReceivedWebmentionEntity::class.java)
        verify(repository).save(captor.capture())
        val saved = captor.value
        assertEquals(ReceivedWebmentionState.PENDING, saved.state)
        assertEquals(targetUrl, saved.targetUrl)
        assertNotNull(saved.firstSeenAt)
    }

    @Test
    fun `ensurePending reopens a previously rejected record`() {
        val existing =
            entity(
                state = ReceivedWebmentionState.REJECTED,
                interaction = WebmentionInteraction.LIKE,
                contentText = listOf("old"),
            )
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)

        service.ensurePending(sourceUrl, targetUrl, postId)

        assertEquals(ReceivedWebmentionState.PENDING, existing.state)
        assertNull(existing.lastError)
        assertNull(existing.verifiedAt)
        verify(repository, times(1)).save(existing)
    }

    @Test
    fun `ensurePending leaves a pending record untouched`() {
        val existing = entity(state = ReceivedWebmentionState.PENDING)
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)
        existing.id = UUID.randomUUID()

        val result = service.ensurePending(sourceUrl, targetUrl, postId)

        assertEquals(existing.id, result.id)
        verify(repository, times(0)).save(existing)
    }

    @Test
    fun `markVerified stores the analysis`() {
        val existing = entity()
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)

        service.markVerified(
            sourceUrl,
            postId,
            ReceivedWebmentionAnalysis(
                interaction = WebmentionInteraction.REPLY,
                primary = null,
                authorName = "Jane",
                authorUrl = "https://jane.example",
                contentText = listOf("Nice post"),
                contentHtml = listOf("<p>Nice post</p>"),
            ),
        )

        assertEquals(ReceivedWebmentionState.VERIFIED, existing.state)
        assertEquals(WebmentionInteraction.REPLY, existing.interaction)
        assertEquals("Jane", existing.authorName)
        assertEquals("https://jane.example", existing.authorUrl)
        assertEquals(listOf("Nice post"), existing.contentText)
        assertEquals(listOf("<p>Nice post</p>"), existing.contentHtml)
        assertNotNull(existing.verifiedAt)
        assertNull(existing.lastError)
    }

    @Test
    fun `markVerified throws when the record is missing`() {
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.markVerified(sourceUrl, postId, ReceivedWebmentionAnalysis(WebmentionInteraction.MENTION, null))
        }
    }

    @Test
    fun `markRejected records the reason`() {
        val existing = entity()
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)

        service.markRejected(sourceUrl, postId, "source does not link to the target")

        assertEquals(ReceivedWebmentionState.REJECTED, existing.state)
        assertEquals("source does not link to the target", existing.lastError)
    }

    @Test
    fun `markDeleted clears extracted content`() {
        val existing =
            entity(
                state = ReceivedWebmentionState.VERIFIED,
                interaction = WebmentionInteraction.REPLY,
                contentText = listOf("Nice post"),
            )
        existing.authorName = "Jane"
        existing.rawMf2 =
            dev.jacobandersen.microformats2.Mf2Object(
                type = listOf("h-entry"),
                properties =
                    mapOf(
                        "content" to
                            listOf(
                                dev.jacobandersen.microformats2.Mf2Value
                                    .String("Nice post"),
                            ),
                    ),
            )
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)

        service.markDeleted(sourceUrl, postId)

        assertEquals(ReceivedWebmentionState.DELETED, existing.state)
        assertNull(existing.interaction)
        assertNull(existing.authorName)
        assertNull(existing.contentText)
        assertNull(existing.rawMf2)
    }

    @Test
    fun `markError records the reason`() {
        val existing = entity()
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(existing)

        service.markError(sourceUrl, postId, "timeout")

        assertEquals(ReceivedWebmentionState.ERROR, existing.state)
        assertEquals("timeout", existing.lastError)
    }

    @Test
    fun `byPost maps all records`() {
        val saved = entity(state = ReceivedWebmentionState.VERIFIED)
        saved.id = UUID.randomUUID()
        `when`(repository.findByPostId(postId)).thenReturn(listOf(saved))

        val result = service.byPost(postId)

        assertEquals(1, result.size)
        assertEquals(saved.id, result[0].id)
    }

    private val carolUrl = "https://carol.example/replies/1"

    private fun reply(
        content: String,
        nested: List<NestedResponse> = emptyList(),
    ) = ReceivedWebmentionAnalysis(
        interaction = WebmentionInteraction.REPLY,
        primary = null,
        contentText = listOf(content),
        url = sourceUrl,
        inReplyTo = listOf(targetUrl),
        nested = nested,
    )

    private fun carol(content: String = "Carol's reply") =
        NestedResponse(
            carolUrl,
            ReceivedWebmentionAnalysis(
                interaction = WebmentionInteraction.REPLY,
                primary = null,
                contentText = listOf(content),
                url = carolUrl,
                inReplyTo = listOf(sourceUrl),
            ),
        )

    private fun verifiedParent(): ReceivedWebmentionEntity =
        entity().also {
            it.id = UUID.randomUUID()
            `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(it)
        }

    @Test
    fun `markVerified stores threading data and nested responses via the source`() {
        val parent = verifiedParent()

        val update = service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        assertTrue(update.changed)
        assertEquals(sourceUrl, parent.url)
        assertEquals(listOf(targetUrl), parent.inReplyTo)
        val captor = ArgumentCaptor.forClass(ReceivedWebmentionEntity::class.java)
        verify(repository, atLeastOnce()).save(captor.capture())
        val nested = captor.allValues.single { it.sourceUrl == carolUrl }
        assertEquals(parent.id, nested.viaId)
        assertEquals(ReceivedWebmentionState.VERIFIED, nested.state)
        assertEquals(targetUrl, nested.targetUrl)
        assertEquals(listOf(sourceUrl), nested.inReplyTo)
        assertNotNull(nested.verifiedAt)
    }

    @Test
    fun `markVerified reports no change when the displayed content is identical`() {
        val parent = verifiedParent()
        service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        // A later re-verification goes through ensurePending first; the fingerprint must survive it.
        parent.state = ReceivedWebmentionState.PENDING
        val update = service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        assertFalse(update.changed)
    }

    @Test
    fun `markVerified reports a change when only a nested response changed`() {
        verifiedParent()
        service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        val update = service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol("edited"))))

        assertTrue(update.changed)
    }

    @Test
    fun `markVerified never overwrites a directly received webmention`() {
        verifiedParent()
        val direct =
            entity(state = ReceivedWebmentionState.DELETED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = carolUrl
            }
        `when`(repository.findBySourceUrlAndPostId(carolUrl, postId)).thenReturn(direct)

        service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        assertEquals(ReceivedWebmentionState.DELETED, direct.state)
        verify(repository, never()).save(direct)
    }

    @Test
    fun `markVerified removes nested responses the source no longer displays`() {
        val parent = verifiedParent()
        val stale =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = carolUrl
                it.viaId = parent.id
            }
        `when`(repository.findByViaId(requireNotNull(parent.id))).thenReturn(listOf(stale))

        service.markVerified(sourceUrl, postId, reply("Bob"))

        verify(repository).deleteAll(listOf(stale))
    }

    @Test
    fun `ensurePending turns a nested copy into a direct webmention`() {
        val nested =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.viaId = UUID.randomUUID()
            }
        `when`(repository.findBySourceUrlAndPostId(sourceUrl, postId)).thenReturn(nested)

        service.ensurePending(sourceUrl, targetUrl, postId)

        assertNull(nested.viaId)
        assertEquals(ReceivedWebmentionState.PENDING, nested.state)
    }

    @Test
    fun `markDeleted drops nested responses and reports a change when it was displayed`() {
        val parent = verifiedParent()
        parent.displayFingerprint = "shown"
        val nested = entity().also { it.viaId = parent.id }
        `when`(repository.findByViaId(requireNotNull(parent.id))).thenReturn(listOf(nested))

        val update = service.markDeleted(sourceUrl, postId)

        assertTrue(update.changed)
        assertNull(parent.displayFingerprint)
        verify(repository).deleteAll(listOf(nested))
    }

    @Test
    fun `markError keeps what was displayed and reports no change`() {
        val parent = verifiedParent()
        parent.displayFingerprint = "shown"

        val update = service.markError(sourceUrl, postId, "timeout")

        assertFalse(update.changed)
        assertEquals("shown", parent.displayFingerprint)
        verify(repository, never()).findByViaId(any())
    }

    @Test
    fun `a nested copy hidden behind a direct webmention does not affect the fingerprint`() {
        verifiedParent()
        service.markVerified(sourceUrl, postId, reply("Bob"))
        val withoutCarol = service.markVerified(sourceUrl, postId, reply("Bob")).webmention.displayFingerprint

        val direct =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = carolUrl
            }
        `when`(repository.findBySourceUrlAndPostId(carolUrl, postId)).thenReturn(direct)
        val update = service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        assertFalse(update.changed)
        assertEquals(withoutCarol, update.webmention.displayFingerprint)
    }

    @Test
    fun `a nested response owned by another source still counts as shown`() {
        verifiedParent()
        service.markVerified(sourceUrl, postId, reply("Bob"))
        val ownedElsewhere =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = carolUrl
                it.viaId = UUID.randomUUID()
            }
        `when`(repository.findBySourceUrlAndPostId(carolUrl, postId)).thenReturn(ownedElsewhere)

        val update = service.markVerified(sourceUrl, postId, reply("Bob", listOf(carol())))

        assertTrue(update.changed)
        verify(repository, never()).save(ownedElsewhere)
    }

    @Test
    fun `markDeleted hands a nested response to another source that still displays it`() {
        val parent = verifiedParent()
        parent.displayFingerprint = "shown"
        val nested =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = carolUrl
                it.viaId = parent.id
            }
        val dave =
            entity(state = ReceivedWebmentionState.VERIFIED).also {
                it.id = UUID.randomUUID()
                it.sourceUrl = "https://dave.example/comments/1"
                it.targetUrl = "https://bastion.test/2026/01/01/post#dave"
                it.rawMf2 =
                    Mf2Object(
                        type = listOf("h-entry"),
                        properties =
                            mapOf(
                                "comment" to
                                    listOf(
                                        Mf2Value.Object(
                                            Mf2Object(
                                                type = listOf("h-cite"),
                                                properties = mapOf("url" to listOf(Mf2Value.String(carolUrl))),
                                            ),
                                        ),
                                    ),
                            ),
                    )
            }
        `when`(repository.findByViaId(requireNotNull(parent.id))).thenReturn(listOf(nested))
        `when`(repository.findByPostIdAndState(postId, ReceivedWebmentionState.VERIFIED)).thenReturn(listOf(parent, dave))

        val update = service.markDeleted(sourceUrl, postId)

        assertTrue(update.changed)
        assertEquals(dave.id, nested.viaId)
        assertEquals(dave.targetUrl, nested.targetUrl)
        verify(repository).save(nested)
        verify(repository, never()).deleteAll(any<List<ReceivedWebmentionEntity>>())
    }
}
