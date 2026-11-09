package dev.jacobandersen.bastion.microsub.internal.entry

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** An opaque paging cursor over deleted entries: a position in `(removed_at, id)` descending order. */
data class RemovedCursor(
    val removedAt: Instant,
    val id: UUID,
) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${ChronoUnit.MICROS.between(Instant.EPOCH, removedAt)}:$id".toByteArray())

    companion object {
        fun decode(value: String?): RemovedCursor? {
            if (value.isNullOrBlank()) return null
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(value.trim())) }.getOrNull() ?: return null
            val (micros, id) = decoded.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return RemovedCursor(
                Instant.EPOCH.plus(micros.toLongOrNull() ?: return null, ChronoUnit.MICROS),
                runCatching { UUID.fromString(id) }.getOrNull() ?: return null,
            )
        }
    }
}
