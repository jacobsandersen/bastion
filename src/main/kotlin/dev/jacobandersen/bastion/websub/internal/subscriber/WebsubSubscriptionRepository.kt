package dev.jacobandersen.bastion.websub.internal.subscriber

import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class WebsubSubscription(
    val id: UUID,
    val topic: String,
    val hub: String,
    val secret: String,
    val state: WebsubSubscriptionState,
    val leaseSeconds: Long?,
    val leaseExpiresAt: Instant?,
    val requestedAt: Instant?,
    val lastError: String?,
)

@Repository
class WebsubSubscriptionRepository(
    private val jdbc: JdbcClient,
) {
    fun find(id: UUID): WebsubSubscription? =
        jdbc
            .sql("select * from websub_subscriptions where id = :id")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun findByTopicAndHub(
        topic: String,
        hub: String,
    ): WebsubSubscription? =
        jdbc
            .sql("select * from websub_subscriptions where topic = :topic and hub = :hub")
            .param("topic", topic)
            .param("hub", hub)
            .query(::map)
            .optional()
            .orElse(null)

    fun insert(
        id: UUID,
        topic: String,
        hub: String,
        secret: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into websub_subscriptions (id, topic, hub, secret, state, requested_at, created_at, updated_at)
                values (:id, :topic, :hub, :secret, 'PENDING', :now, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("topic", topic)
            .param("hub", hub)
            .param("secret", secret)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun markRequested(
        id: UUID,
        state: WebsubSubscriptionState,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update websub_subscriptions
                set state = :state, requested_at = :now, last_error = null, updated_at = :now
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("state", state.name)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun markActive(
        id: UUID,
        leaseSeconds: Long?,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update websub_subscriptions
                set state = 'ACTIVE', lease_seconds = :lease, lease_expires_at = :expires,
                    verified_at = :now, last_error = null, updated_at = :now
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("lease", leaseSeconds)
            .param("expires", leaseSeconds?.let { Timestamp.from(now.plusSeconds(it)) })
            .param("now", Timestamp.from(now))
            .update()
    }

    fun markFailed(
        id: UUID,
        state: WebsubSubscriptionState,
        reason: String?,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                update websub_subscriptions
                set state = :state, last_error = :reason, lease_expires_at = null, updated_at = :now
                where id = :id
                """.trimIndent(),
            ).param("id", id)
            .param("state", state.name)
            .param("reason", reason)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun touchNotified(
        id: UUID,
        now: Instant,
    ) {
        jdbc
            .sql("update websub_subscriptions set last_notified_at = :now where id = :id")
            .param("id", id)
            .param("now", Timestamp.from(now))
            .update()
    }

    fun delete(id: UUID) {
        jdbc.sql("delete from websub_subscriptions where id = :id").param("id", id).update()
    }

    /** Active leases ending before [renewBy], and requests still unverified since before [staleBefore]. */
    fun dueForRequest(
        renewBy: Instant,
        staleBefore: Instant,
    ): List<WebsubSubscription> =
        jdbc
            .sql(
                """
                select * from websub_subscriptions
                where (state = 'ACTIVE' and (lease_expires_at is null or lease_expires_at < :renewBy))
                   or (state in ('PENDING', 'UNSUBSCRIBING') and requested_at < :staleBefore)
                order by id
                """.trimIndent(),
            ).param("renewBy", Timestamp.from(renewBy))
            .param("staleBefore", Timestamp.from(staleBefore))
            .query(::map)
            .list()

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): WebsubSubscription =
        WebsubSubscription(
            id = rs.getObject("id", UUID::class.java),
            topic = rs.getString("topic"),
            hub = rs.getString("hub"),
            secret = rs.getString("secret"),
            state = WebsubSubscriptionState.valueOf(rs.getString("state")),
            leaseSeconds = rs.getLong("lease_seconds").takeUnless { rs.wasNull() },
            leaseExpiresAt = rs.getTimestamp("lease_expires_at")?.toInstant(),
            requestedAt = rs.getTimestamp("requested_at")?.toInstant(),
            lastError = rs.getString("last_error"),
        )
}
