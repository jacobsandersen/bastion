package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.url.ContentUrlConfig
import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionUpdate
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.service.ReceivedWebmentionService
import dev.jacobandersen.bastion.webmention.internal.http.SourceFetch
import dev.jacobandersen.bastion.webmention.internal.http.WebmentionSourceFetcher
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2ParseResult
import dev.jacobandersen.microformats2.Mf2Parser
import dev.jacobandersen.microformats2.Mf2ParserImpl
import dev.jacobandersen.microformats2.Mf2Value
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import java.time.Instant
import java.util.UUID

class WebmentionReceiverServiceTest {
    private val notificationService = mock(ReceivedWebmentionService::class.java)
    private val sourceFetcher = mock(WebmentionSourceFetcher::class.java)
    private val parser = mock(Mf2Parser::class.java)
    private val jobScheduler = mock(JobScheduler::class.java)
    private val salmentionService = mock(SalmentionService::class.java)
    private val contentUrlMatcher =
        ContentUrlMatcher(ContentUrlConfig(baseUrl = "https://bastion.test", pathPattern = "{year}/{month}/{day}/{slug}"))
    private val receiver =
        WebmentionReceiverService(notificationService, sourceFetcher, parser, contentUrlMatcher, jobScheduler, salmentionService)

    private val sourceUrl = "https://source.example/reply"
    private val targetUrl = "https://bastion.test/2026/01/01/post"
    private val postId = UUID.randomUUID()

    private fun fetch(
        status: Int = 200,
        body: String = "<a href=\"$targetUrl\">link</a>",
        contentType: String = "text/html",
    ) = SourceFetch(status, sourceUrl, contentType, body)

    private fun update(changed: Boolean) =
        ReceivedWebmentionUpdate(
            ReceivedWebmention(
                id = UUID.randomUUID(),
                postId = postId,
                sourceUrl = sourceUrl,
                targetUrl = targetUrl,
                state = ReceivedWebmentionState.VERIFIED,
                interaction = WebmentionInteraction.REPLY,
                authorName = null,
                authorUrl = null,
                authorPhoto = null,
                contentText = emptyList(),
                contentHtml = emptyList(),
                rawMf2 = null,
                url = null,
                inReplyTo = null,
                viaId = null,
                displayFingerprint = null,
                lastError = null,
                firstSeenAt = Instant.now(),
                verifiedAt = Instant.now(),
                updatedAtUtc = Instant.now(),
            ),
            changed,
        )

    @BeforeEach
    fun stubOutcomes() {
        `when`(notificationService.markVerified(anyString(), any(), any())).thenReturn(update(changed = false))
        `when`(notificationService.markDeleted(anyString(), any())).thenReturn(update(changed = false))
        `when`(notificationService.markRejected(anyString(), any(), anyString())).thenReturn(update(changed = false))
        `when`(notificationService.markError(anyString(), any(), anyString())).thenReturn(update(changed = false))
    }

    @Test
    fun `verifies a linking html source and stores the analysis`() {
        val parseResult =
            Mf2ParseResult(
                items =
                    listOf(
                        Mf2Object(
                            type = listOf("h-entry"),
                            properties = mapOf("content" to listOf(Mf2Value.String("hi"))),
                        ),
                    ),
                rels = emptyMap(),
                relUrls = emptyMap(),
            )
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch())
        `when`(parser.parse(anyString(), anyString())).thenReturn(parseResult)

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markVerified(
            eq(sourceUrl),
            eq(postId),
            check { analysis -> assertEquals(WebmentionInteraction.MENTION, analysis.interaction) },
        )
    }

    @Test
    fun `marks the webmention deleted when the source is gone`() {
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch(status = 410, body = ""))

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markDeleted(sourceUrl, postId)
    }

    @Test
    fun `rejects the webmention when the source does not link`() {
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch(body = "<p>no link</p>"))

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markRejected(sourceUrl, postId, "source does not link to the target")
    }

    @Test
    fun `flags the webmention when the source is unreachable`() {
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(
            SourceFetch(0, sourceUrl, null, "", error = "connection refused"),
        )

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markError(sourceUrl, postId, "connection refused")
    }

    @Test
    fun `verifies a source whose document has no microformats as a plain mention`() {
        val emptyParse = Mf2ParseResult(items = emptyList(), rels = emptyMap(), relUrls = emptyMap())
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch())
        `when`(parser.parse(anyString(), anyString())).thenReturn(emptyParse)

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markVerified(
            eq(sourceUrl),
            eq(postId),
            check { analysis ->
                assertEquals(WebmentionInteraction.MENTION, analysis.interaction)
                assertNull(analysis.primary)
            },
        )
    }

    @Test
    fun `schedules a salmention when the displayed responses changed`() {
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch(status = 410, body = ""))
        `when`(notificationService.markDeleted(sourceUrl, postId)).thenReturn(update(changed = true))

        receiver.verify(sourceUrl, targetUrl, postId)

        val job = argumentCaptor<JobLambda>()
        verify(jobScheduler).enqueue(job.capture())
        job.firstValue.run()
        verify(salmentionService).resendAfterResponseChange(postId)
    }

    @Test
    fun `does not schedule a salmention when nothing changed`() {
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch(status = 410, body = ""))

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(jobScheduler, never()).enqueue(any<JobLambda>())
    }

    @Test
    fun `drops our own replies displayed nested in the source`() {
        val body =
            """
            <article class="h-entry">
              <a class="u-url" href="$sourceUrl">permalink</a>
              <a class="u-in-reply-to" href="$targetUrl">post</a>
              <div class="e-content">Bob's comment</div>
              <div class="p-comment h-cite">
                <a class="u-url" href="https://carol.example/reply">Carol</a>
                <p class="p-content">Carol's reply</p>
              </div>
              <div class="p-comment h-cite">
                <a class="u-url" href="https://bastion.test/2026/01/02/my-reply">Me</a>
                <p class="p-content">My own reply</p>
              </div>
            </article>
            """.trimIndent()
        `when`(sourceFetcher.fetch(sourceUrl)).thenReturn(fetch(body = body))
        `when`(parser.parse(anyString(), anyString())).thenAnswer {
            Mf2ParserImpl().parse(it.arguments[0] as String, it.arguments[1] as String)
        }

        receiver.verify(sourceUrl, targetUrl, postId)

        verify(notificationService).markVerified(
            eq(sourceUrl),
            eq(postId),
            check { analysis ->
                assertEquals(listOf("https://carol.example/reply"), analysis.nested.map { it.url })
            },
        )
    }
}
