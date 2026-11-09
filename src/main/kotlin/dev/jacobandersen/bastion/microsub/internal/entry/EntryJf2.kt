package dev.jacobandersen.bastion.microsub.internal.entry

import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item

/** The jf2 item a client gets for a stored entry: its data plus the `_id`, `_is_read` and `_source` members. */
fun EntryRow.toJf2(): Map<String, Any?> =
    LinkedHashMap(data).apply {
        put("_id", id.toString())
        put("_is_read", isRead)
        // Undated entries are ordered by when they were first seen; expose that so every entry carries `published`.
        if (!containsKey("published")) put("published", Jf2Item.formatInstant(published))
        source?.let { source ->
            put(
                "_source",
                buildMap {
                    put("_id", source.id.toString())
                    put("url", source.url)
                    source.name?.let { put("name", it) }
                    source.photo?.let { put("photo", it) }
                },
            )
        }
    }
