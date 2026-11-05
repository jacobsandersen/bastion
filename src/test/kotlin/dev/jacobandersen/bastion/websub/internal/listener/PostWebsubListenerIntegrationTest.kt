package dev.jacobandersen.bastion.websub.internal.listener

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.config.internal.events.EventPublicationResubmissionService
import dev.jacobandersen.bastion.micropub.internal.command.CreateService
import dev.jacobandersen.bastion.micropub.internal.command.DeleteService
import dev.jacobandersen.bastion.micropub.internal.command.UpdateService
import dev.jacobandersen.bastion.micropub.internal.type.req.MicropubPayload
import dev.jacobandersen.bastion.micropub.internal.type.resp.ApiResponse
import dev.jacobandersen.bastion.websub.internal.WebsubPublisher
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.after
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
@WithMockUser(authorities = ["CREATE", "UPDATE", "DELETE", "UNDELETE"])
class PostWebsubListenerIntegrationTest {
    @Autowired
    lateinit var createService: CreateService

    @Autowired
    lateinit var updateService: UpdateService

    @Autowired
    lateinit var deleteService: DeleteService

    @MockitoBean
    lateinit var websubPublisher: WebsubPublisher

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var resubmissionService: EventPublicationResubmissionService

    private val mapper = JsonMapper.builderWithJackson2Defaults().build()

    @BeforeEach
    fun awaitNoInFlightEvents() {
        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            val count =
                jdbcTemplate.queryForObject(
                    "select count(*) from event_publication where status in ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')",
                    Int::class.java,
                )
            assertEquals(0, count)
        }
        clearInvocations(websubPublisher)
    }

    private fun uniqueSlug(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun failedEventCount(): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from event_publication where status = 'FAILED'",
            Int::class.java,
        ) ?: 0

    private fun createPayload(
        content: String,
        slug: String,
        status: String = "published",
        visibility: String = "public",
    ): MicropubPayload.Json {
        val root =
            mapper.readTree(
                """{"type": ["h-entry"], "properties": {"name": ["$slug"], "content": ["$content"], "mp-slug": ["$slug"], "post-status": ["$status"], "visibility": ["$visibility"]}}""",
            ) as ObjectNode
        return MicropubPayload.Json(root)
    }

    private fun createPublicPost(content: String): String {
        val slug = uniqueSlug("websub")
        val response = createService.create(createPayload(content, slug), null)
        return (response as ApiResponse.Success.Created).location
    }

    private fun updateJson(
        url: String,
        update: String,
    ): MicropubPayload.Json {
        val root = mapper.readTree("""{"url": "$url", $update}""") as ObjectNode
        return MicropubPayload.Json(root)
    }

    @Test
    fun createPublishesForPublicPosts() {
        createPublicPost("Hello WebSub")

        verify(websubPublisher, timeout(2_000)).publish(any())
    }

    @Test
    fun createDoesNotPublishForDraftOrPrivatePosts() {
        val draftSlug = uniqueSlug("websub-draft")
        createService.create(createPayload("Draft", draftSlug, status = "draft"), null)
        verify(websubPublisher, after(1_000).never()).publish(any())

        val privateSlug = uniqueSlug("websub-private")
        createService.create(createPayload("Private", privateSlug, visibility = "private"), null)
        verify(websubPublisher, after(1_000).never()).publish(any())
    }

    @Test
    fun updatePublishesForPublicPosts() {
        val location = createPublicPost("Before")
        awaitNoInFlightEvents()
        clearInvocations(websubPublisher)

        val response = updateService.update(updateJson(location, """"replace": {"content": ["After"]}"""))
        assertInstanceOf(ApiResponse.Success.NoContent::class.java, response)

        verify(websubPublisher, timeout(2_000)).publish(any())
    }

    @Test
    fun updateToPrivatePublishesBecauseTheFeedChanged() {
        val location = createPublicPost("Before")
        awaitNoInFlightEvents()
        clearInvocations(websubPublisher)

        val response = updateService.update(updateJson(location, """"replace": {"visibility": ["private"]}"""))
        assertInstanceOf(ApiResponse.Success.NoContent::class.java, response)

        verify(websubPublisher, timeout(2_000)).publish(any())
    }

    @Test
    fun deleteAndUndeletePublish() {
        val location = createPublicPost("Hello WebSub")
        awaitNoInFlightEvents()
        clearInvocations(websubPublisher)

        val deleteResponse = deleteService.delete(MicropubPayload.Json(mapper.createObjectNode().put("url", location)))
        assertInstanceOf(ApiResponse.Success.NoContent::class.java, deleteResponse)

        val undeleteResponse =
            deleteService.undelete(MicropubPayload.Json(mapper.createObjectNode().put("url", location)))
        assertInstanceOf(ApiResponse.Success.NoContent::class.java, undeleteResponse)

        verify(websubPublisher, timeout(2_000).times(2)).publish(any())
    }

    @Test
    fun failedListenerPublicationsAreRetried() {
        whenever(websubPublisher.publish(any())).thenThrow(RuntimeException("hub unavailable"))

        createPublicPost("Retry WebSub")

        await().atMost(Duration.ofSeconds(2)).untilAsserted {
            assertEquals(1, failedEventCount())
        }

        reset(websubPublisher)
        resubmissionService.resubmit(Duration.ZERO)

        verify(websubPublisher, timeout(2_000)).publish(any())
        awaitNoInFlightEvents()
        assertEquals(0, failedEventCount())
    }
}
