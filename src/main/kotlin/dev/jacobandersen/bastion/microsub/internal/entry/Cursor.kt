package dev.jacobandersen.bastion.microsub.internal.entry

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * An opaque paging cursor: an entry's position in `(published desc, id desc)`
 * order. Microsecond precision matches Postgres `timestamptz`.
 */
data class Cursor(
    val published: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, published)
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros:$id".toByteArray())
    }

    companion object {
        fun decode(value: String?): Cursor? {
            if (value.isNullOrBlank()) return null
            val text = runCatching { String(Base64.getUrlDecoder().decode(value.trim())) }.getOrNull() ?: return null
            val (micros, id) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val instant = micros.toLongOrNull()?.let { Instant.EPOCH.plus(it, ChronoUnit.MICROS) } ?: return null
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            return Cursor(instant, uuid)
        }
    }
}
