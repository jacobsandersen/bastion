package dev.jacobandersen.bastion.microsub.internal.jf2

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/**
 * A normalized jf2 entry (Microsub spec section 6) plus the columns derived
 * from it for storage, paging, moderation and search. [data] holds only
 * public jf2 properties; the `_id`, `_is_read` and `_source` server
 * properties are added when the entry is served.
 */
data class Jf2Item(
    val data: Map<String, Any>,
    val sourceKey: String,
    val published: Instant?,
    val authorUrl: String?,
    val url: String?,
    val categories: List<String>,
    val kinds: Set<String>,
    val searchText: String,
) {
    companion object {
        private val KIND_PROPERTIES =
            linkedMapOf(
                "like-of" to "like",
                "repost-of" to "repost",
                "bookmark-of" to "bookmark",
                "in-reply-to" to "reply",
                "checkin" to "checkin",
                "photo" to "photo",
                "video" to "video",
                "audio" to "audio",
            )

        /** Derives the stored columns from a jf2 map with no null values or empty lists. */
        fun of(data: Map<String, Any>): Jf2Item {
            val url = data["url"] as? String
            val uid = data["uid"] as? String
            val published = (data["published"] as? String)?.let(::parseInstant)
            val author = data["author"] as? Map<*, *>
            val categories = (data["category"] as? List<*>).orEmpty().filterIsInstance<String>()
            val content = data["content"] as? Map<*, *>
            val searchText =
                listOfNotNull(
                    data["name"] as? String,
                    data["summary"] as? String,
                    content?.get("text") as? String,
                    author?.get("name") as? String,
                ).plus(categories)
                    .joinToString(" ")

            return Jf2Item(
                data = data,
                sourceKey = uid ?: url ?: "hash:${digest(data.toString())}",
                published = published,
                authorUrl = (author?.get("url") as? String)?.let(MicrosubUrls::normalize),
                url = url,
                categories = categories,
                kinds = KIND_PROPERTIES.filterKeys(data::containsKey).values.toSet(),
                searchText = searchText,
            )
        }

        /** Parses ISO-8601 dates as feeds write them: with or without offset, seconds, or a time at all. */
        fun parseInstant(value: String): Instant? {
            val text = value.trim().replace(' ', 'T')
            return runCatching { OffsetDateTime.parse(text).toInstant() }
                .recoverCatching { OffsetDateTime.parse(text, LENIENT_OFFSET).toInstant() }
                .recoverCatching {
                    java.time.LocalDateTime
                        .parse(text)
                        .toInstant(ZoneOffset.UTC)
                }.recoverCatching {
                    java.time.LocalDate
                        .parse(text)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                }.getOrNull()
        }

        fun formatInstant(instant: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))

        private val LENIENT_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss][.SSS]XX")

        private fun digest(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())).take(32)
    }
}

/** Builds a jf2 map, dropping null values, blank strings and empty collections. */
class Jf2Builder {
    private val map = LinkedHashMap<String, Any>()

    operator fun set(
        key: String,
        value: Any?,
    ) {
        when (value) {
            null -> {}

            is String -> {
                if (value.isNotBlank()) map[key] = value
            }

            is Collection<*> -> {
                if (value.isNotEmpty()) map[key] = value.toList()
            }

            is Map<*, *> -> {
                if (value.isNotEmpty()) map[key] = value
            }

            else -> {
                map[key] = value
            }
        }
    }

    fun build(): Map<String, Any> = map
}

fun jf2(block: Jf2Builder.() -> Unit): Map<String, Any> = Jf2Builder().apply(block).build()
