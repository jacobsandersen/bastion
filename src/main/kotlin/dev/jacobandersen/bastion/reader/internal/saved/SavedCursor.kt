package dev.jacobandersen.bastion.reader.internal.saved

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** An opaque paging cursor over saved items: a position in `(saved_at, id)` descending order. */
data class SavedCursor(
    val savedAt: Instant,
    val id: UUID,
) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${ChronoUnit.MICROS.between(Instant.EPOCH, savedAt)}:$id".toByteArray())

    companion object {
        /** Null for a missing or unusable cursor, which starts from the newest item. */
        fun decode(value: String?): SavedCursor? {
            if (value.isNullOrBlank()) return null
            val decoded = runCatching { String(Base64.getUrlDecoder().decode(value.trim())) }.getOrNull() ?: return null
            val (micros, id) = decoded.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return SavedCursor(
                Instant.EPOCH.plus(micros.toLongOrNull() ?: return null, ChronoUnit.MICROS),
                runCatching { UUID.fromString(id) }.getOrNull() ?: return null,
            )
        }
    }
}
