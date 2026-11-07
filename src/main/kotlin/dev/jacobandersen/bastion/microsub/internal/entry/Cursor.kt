package dev.jacobandersen.bastion.microsub.internal.entry

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * An opaque paging cursor: an entry's position in `(created_at, published, id)` descending order.
 *
 * Timelines are ordered by when Bastion first stored an entry, then by publish date within one fetch, so an entry that
 * arrives late with an old `published` date still lands after every cursor handed out before it arrived. A `before`
 * poll therefore cannot miss it. Microsecond precision matches Postgres `timestamptz`.
 */
data class Cursor(
    val createdAt: Instant,
    val published: Instant,
    val id: UUID,
) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${micros(createdAt)}:${micros(published)}:$id".toByteArray())

    companion object {
        private fun micros(instant: Instant) = ChronoUnit.MICROS.between(Instant.EPOCH, instant)

        private fun instant(micros: String) = micros.toLongOrNull()?.let { Instant.EPOCH.plus(it, ChronoUnit.MICROS) }

        private fun text(value: String?): List<String>? {
            if (value.isNullOrBlank()) return null
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(value.trim())) }.getOrNull() ?: return null
            return decoded.split(':', limit = 3)
        }

        fun decode(value: String?): Cursor? {
            val (created, published, id) = text(value)?.takeIf { it.size == 3 } ?: return null
            return Cursor(
                instant(created) ?: return null,
                instant(published) ?: return null,
                runCatching { UUID.fromString(id) }.getOrNull() ?: return null,
            )
        }

        /**
         * The entry id inside a cursor handed out before timelines were ordered by arrival (`published:id`), so
         * the entry's current position can be looked up. Null for anything else.
         */
        fun legacyEntryId(value: String?): UUID? {
            val (published, id) = text(value)?.takeIf { it.size == 2 } ?: return null
            if (instant(published) == null) return null
            return runCatching { UUID.fromString(id) }.getOrNull()
        }
    }
}
