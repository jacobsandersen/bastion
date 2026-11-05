package dev.jacobandersen.bastion.websub.internal.subscriber

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.HexFormat
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
class WebsubSubscriberIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var subscriber: WebsubSubscriber

    @Autowired
    lateinit var jdbc: JdbcTemplate

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun topic() = "https://feeds.example/${UUID.randomUUID()}"

    private fun secretOf(id: UUID): String =
        jdbc.queryForObject("select secret from websub_subscriptions where id = ?", String::class.java, id)!!

    private fun verify(
        id: UUID,
        mode: String,
        topic: String,
    ) = mockMvc.perform(
        get("/websub/callback/$id")
            .param("hub.mode", mode)
            .param("hub.topic", topic)
            .param("hub.challenge", "challenge-123")
            .param("hub.lease_seconds", "3600"),
    )

    @Test
    fun `subscribe, verify, receive signed content, unsubscribe`() {
        val topic = topic()
        val id = subscriber.subscribe(topic, "https://hub.example/")
        assertEquals(id, subscriber.subscribe(topic, "https://hub.example/"), "a pending subscription is reused")
        assertEquals(WebsubSubscriptionState.PENDING, subscriber.find(id)!!.state)

        verify(id, "subscribe", "https://other.example/").andExpect(status().isNotFound)
        verify(id, "subscribe", topic).andExpect(status().isOk).andExpect(content().string("challenge-123"))
        val active = subscriber.find(id)!!
        assertEquals(WebsubSubscriptionState.ACTIVE, active.state)
        assertNotNull(active.leaseExpiresAt)

        val body = "<feed/>".toByteArray()
        val signature = "sha256=" + HexFormat.of().formatHex(HubSignature.sign("HmacSHA256", secretOf(id), body))
        mockMvc
            .perform(post("/websub/callback/$id").content(body).header("X-Hub-Signature", "sha256=" + "00".repeat(32)))
            .andExpect(status().isAccepted)
        assertNull(lastNotified(id), "a badly signed delivery is acknowledged but dropped")

        mockMvc
            .perform(post("/websub/callback/$id").content(body).header("X-Hub-Signature", signature))
            .andExpect(status().isAccepted)
        assertNotNull(lastNotified(id))

        subscriber.unsubscribe(id)
        assertEquals(WebsubSubscriptionState.UNSUBSCRIBING, subscriber.find(id)!!.state)
        verify(id, "unsubscribe", topic).andExpect(status().isOk).andExpect(content().string("challenge-123"))
        assertNull(subscriber.find(id))
        mockMvc.perform(post("/websub/callback/$id").content(body).header("X-Hub-Signature", signature)).andExpect(status().isGone)
    }

    @Test
    fun `records a hub denial and refuses unknown callbacks`() {
        val topic = topic()
        val id = subscriber.subscribe(topic, "https://hub.example/")

        mockMvc
            .perform(get("/websub/callback/$id").param("hub.mode", "denied").param("hub.topic", topic).param("hub.reason", "nope"))
            .andExpect(status().isOk)
        assertEquals(WebsubSubscriptionState.DENIED, subscriber.find(id)!!.state)

        verify(UUID.randomUUID(), "subscribe", topic).andExpect(status().isNotFound)
        mockMvc.perform(post("/websub/callback/not-a-uuid").content("x")).andExpect(status().isGone)
    }

    private fun lastNotified(id: UUID): Any? =
        jdbc.queryForObject("select last_notified_at from websub_subscriptions where id = ?", Any::class.java, id)
}
