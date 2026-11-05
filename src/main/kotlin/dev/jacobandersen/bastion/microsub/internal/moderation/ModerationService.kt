package dev.jacobandersen.bastion.microsub.internal.moderation

import dev.jacobandersen.bastion.microsub.internal.channel.ChannelService
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Mute and block, per channel or `global`. A mute hides the author's entries
 * at read time and is invisible to them; a block also deletes what is stored
 * and drops future entries at ingest. Bastion sends nothing to either.
 */
@Service
class ModerationService(
    private val channels: ChannelService,
    private val repository: ModerationRepository,
    private val entries: EntryRepository,
) {
    @Transactional(readOnly = true)
    fun list(
        channelUid: String?,
        kind: ModerationKind,
    ): Map<String, Any> {
        val channelId = channels.requireOrGlobal(channelUid)
        return mapOf("items" to repository.list(channelId, kind).map { mapOf("type" to "card", "url" to it) })
    }

    @Transactional
    fun add(
        channelUid: String?,
        url: String?,
        kind: ModerationKind,
    ) {
        val channelId = channels.requireOrGlobal(channelUid)
        val author = normalize(url)
        repository.add(channelId, author, kind, Instant.now())
        if (kind == ModerationKind.BLOCK) entries.deleteByAuthor(channelId, author)
    }

    @Transactional
    fun remove(
        channelUid: String?,
        url: String?,
        kind: ModerationKind,
    ) {
        val channelId = channels.requireOrGlobal(channelUid)
        repository.remove(channelId, normalize(url), kind)
    }

    private fun normalize(url: String?): String =
        url?.let(MicrosubUrls::fromUserInput) ?: throw MicrosubError.invalidRequest("The 'url' parameter must be a profile URL")
}
