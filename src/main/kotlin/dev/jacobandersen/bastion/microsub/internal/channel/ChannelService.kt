package dev.jacobandersen.bastion.microsub.internal.channel

import com.github.slugify.Slugify
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRepository
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Microsub channels. `notifications` always exists, always sorts first and
 * cannot be deleted or reordered. Renaming never changes a uid, which the
 * spec allows (a server MAY change it and MUST return it). Deleting a channel
 * releases the WebSub subscriptions its feeds no longer need.
 */
@Service
class ChannelService(
    private val repository: ChannelRepository,
    private val slugify: Slugify,
    private val feeds: FeedRepository,
    private val refresh: FeedRefreshService,
) {
    @Transactional(readOnly = true)
    fun list(): List<Map<String, Any>> {
        val unread = repository.unreadCounts()
        return ordered().map { channel ->
            mapOf("uid" to channel.uid, "name" to channel.name, "unread" to (unread[channel.id] ?: 0))
        }
    }

    @Transactional(readOnly = true)
    fun require(uid: String?): Channel {
        if (uid.isNullOrBlank()) throw MicrosubError.invalidRequest("The 'channel' parameter is required")
        return repository.findByUid(uid) ?: throw MicrosubError.notFound("Channel '$uid' does not exist")
    }

    /** The channel id for [uid], or null for `global`. */
    @Transactional(readOnly = true)
    fun requireOrGlobal(uid: String?): UUID? = if (uid == GLOBAL) null else require(uid).id

    @Transactional
    fun create(name: String?): Channel {
        val trimmed = requireName(name)
        val base = slugify.slugify(trimmed).take(MAX_UID).ifBlank { "channel" }
        var uid = base
        var suffix = 2
        while (uid in RESERVED || repository.uidExists(uid)) {
            uid = "$base-${suffix++}"
        }
        return repository.insert(uid, trimmed, Instant.now())
    }

    @Transactional
    fun rename(
        uid: String?,
        name: String?,
    ): Channel {
        val channel = require(uid)
        val trimmed = requireName(name)
        repository.rename(channel.id, trimmed, Instant.now())
        return channel.copy(name = trimmed)
    }

    @Transactional
    fun delete(uid: String?) {
        val channel = require(uid)
        if (channel.uid == NOTIFICATIONS) throw MicrosubError.invalidRequest("The notifications channel cannot be deleted")
        val subscriptions = feeds.listByChannel(channel.id).mapNotNull { it.subscriptionId }.distinct()
        repository.delete(channel.id)
        subscriptions.forEach(refresh::releaseSubscription)
    }

    /**
     * Reorders the listed channels within the slots they already occupy
     * (spec: only the listed channels move). `notifications` and unknown
     * uids are ignored.
     */
    @Transactional
    fun order(uids: List<String>) {
        val byUid = repository.list().associateBy { it.uid }
        val listed = uids.distinct().filter { it != NOTIFICATIONS }.mapNotNull(byUid::get)
        val slots = listed.map { it.position }.sorted()
        listed.zip(slots).forEach { (channel, position) -> repository.setPosition(channel.id, position) }
    }

    private fun ordered(): List<Channel> {
        val all = repository.list()
        return all.filter { it.uid == NOTIFICATIONS } + all.filter { it.uid != NOTIFICATIONS }
    }

    private fun requireName(name: String?): String =
        name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME)
            ?: throw MicrosubError.invalidRequest("The 'name' parameter is required")

    companion object {
        const val NOTIFICATIONS = "notifications"
        const val GLOBAL = "global"
        private val RESERVED = setOf(NOTIFICATIONS, GLOBAL)
        private const val MAX_UID = 64
        private const val MAX_NAME = 200
    }
}
