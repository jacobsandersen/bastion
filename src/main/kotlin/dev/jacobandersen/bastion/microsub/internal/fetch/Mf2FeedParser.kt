package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.bastion.microsub.internal.jf2.HtmlSanitizer
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2ParseResult
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.firstHtml
import dev.jacobandersen.microformats2.firstText
import dev.jacobandersen.microformats2.plainTextOrNull
import dev.jacobandersen.microformats2.texts

/**
 * Converts a parsed mf2 document to jf2: the entries of the first `h-feed`,
 * or top-level `h-entry`/`h-event`/`h-cite` items when there is no feed.
 * Entries without an author inherit the feed's author or the page's
 * representative `h-card`.
 */
object Mf2FeedParser {
    private val ENTRY_TYPES = setOf("h-entry", "h-event", "h-cite")
    private val URL_PROPERTIES = listOf("like-of", "repost-of", "bookmark-of", "in-reply-to", "syndication")

    fun hasEntries(result: Mf2ParseResult): Boolean = entriesOf(result).isNotEmpty()

    fun parse(
        result: Mf2ParseResult,
        url: String,
        title: String?,
    ): ParsedFeed {
        val feed = result.items.firstOrNull { "h-feed" in it.type }
        val representative = representativeCard(result, url)
        val feedAuthor = feed?.getFirstProperty("author")?.let { author(it, url) } ?: representative?.let { card(it, url) }

        val items =
            entriesOf(result).mapNotNull { entry(it, url, feedAuthor) }.map(Jf2Item::of)

        return ParsedFeed(
            format = FeedFormat.MF2,
            url = url,
            name = feed?.firstText("name") ?: representative?.firstText("name") ?: title,
            photo = (feed?.getFirstProperty("photo") ?: representative?.getFirstProperty("photo"))?.let { urlOf(it, url) },
            description = feed?.firstText("summary") ?: representative?.firstText("note"),
            items = items,
            hubs = result.rels["hub"].orEmpty(),
            self = result.rels["self"]?.firstOrNull(),
        )
    }

    private fun entriesOf(result: Mf2ParseResult): List<Mf2Object> {
        val feed = result.items.firstOrNull { "h-feed" in it.type }
        if (feed != null) return feed.children.orEmpty().filter { it.type.any(ENTRY_TYPES::contains) }
        return result.items.filter { it.type.any(ENTRY_TYPES::contains) }
    }

    private fun representativeCard(
        result: Mf2ParseResult,
        pageUrl: String,
    ): Mf2Object? {
        val page = MicrosubUrls.normalize(pageUrl)
        val cards = result.items.filter { "h-card" in it.type }
        return cards.firstOrNull { card -> card.texts("url").any { MicrosubUrls.normalize(it) == page } }
            ?: cards.singleOrNull()
    }

    fun entry(
        obj: Mf2Object,
        base: String,
        fallbackAuthor: Map<String, Any>?,
    ): Map<String, Any>? {
        val url = obj.getFirstProperty("url")?.let { urlOf(it, base) }
        val contentValue = obj.getFirstProperty("content")
        val content =
            contentValue?.let {
                HtmlSanitizer.content(obj.firstHtml("content"), it.plainTextOrNull, url ?: base)
            }
        val contentText = content?.get("text") as? String
        val name = obj.firstText("name")?.takeUnless { isImpliedName(it, contentText, obj.firstText("summary")) }

        val data =
            jf2 {
                this["type"] = if ("h-event" in obj.type) "event" else "entry"
                this["url"] = url
                this["uid"] = obj.firstText("uid")
                this["name"] = name
                this["summary"] = obj.firstText("summary")?.takeUnless { it == contentText }
                this["published"] = obj.firstText("published")?.let(::isoOrRaw)
                this["updated"] = obj.firstText("updated")?.let(::isoOrRaw)
                this["start"] = obj.firstText("start")
                this["end"] = obj.firstText("end")
                this["content"] = content
                this["author"] = obj.getFirstProperty("author")?.let { author(it, base) } ?: fallbackAuthor
                this["category"] = obj.getProperty("category").mapNotNull(::categoryOf).distinct()
                this["photo"] = obj.getProperty("photo").mapNotNull { urlOf(it, base) }
                this["video"] = obj.getProperty("video").mapNotNull { urlOf(it, base) }
                this["audio"] = obj.getProperty("audio").mapNotNull { urlOf(it, base) }
                URL_PROPERTIES.forEach { property ->
                    this[property] = obj.getProperty(property).mapNotNull { urlOf(it, base) }.distinct()
                }
                this["checkin"] = obj.getFirstProperty("checkin")?.let { location(it, base) }
                this["location"] = obj.getFirstProperty("location")?.let { location(it, base) }
                this["rsvp"] = obj.firstText("rsvp")
            }
        val hasBody = listOf("name", "content", "summary", "photo", "video", "audio", "like-of", "repost-of", "bookmark-of")
        return data.takeIf { hasBody.any(data::containsKey) || url != null }
    }

    /**
     * An mf2 implied name is the element's whole text, so for notes it
     * duplicates the content. Treat a name that is a prefix of the content or
     * equals the summary as implied.
     */
    private fun isImpliedName(
        name: String,
        contentText: String?,
        summary: String?,
    ): Boolean {
        val normalized = squash(name)
        if (normalized.isEmpty()) return true
        if (summary != null && squash(summary) == normalized) return true
        val content = contentText?.let(::squash) ?: return false
        return content.startsWith(normalized) || normalized.startsWith(content)
    }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun isoOrRaw(value: String): String = Jf2Item.parseInstant(value)?.let(Jf2Item::formatInstant) ?: value

    fun author(
        value: Mf2Value,
        base: String,
    ): Map<String, Any>? =
        when (value) {
            is Mf2Value.Object -> {
                card(value.value, base)
            }

            else -> {
                val text = value.plainTextOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val url = MicrosubUrls.resolve(base, text)?.takeIf { text.contains('/') || text.contains('.') }
                if (url != null && !text.contains(' ')) {
                    jf2 {
                        this["type"] = "card"
                        this["url"] = url
                    }
                } else {
                    jf2 {
                        this["type"] = "card"
                        this["name"] = text
                    }
                }
            }
        }

    fun card(
        obj: Mf2Object,
        base: String,
    ): Map<String, Any> =
        jf2 {
            this["type"] = "card"
            this["name"] = obj.firstText("name")
            this["url"] = obj.getFirstProperty("url")?.let { urlOf(it, base) }
            this["photo"] = obj.getFirstProperty("photo")?.let { urlOf(it, base) }
        }

    private fun location(
        value: Mf2Value,
        base: String,
    ): Map<String, Any>? {
        val obj =
            (value as? Mf2Value.Object)?.value
                ?: return value.plainTextOrNull?.let { name ->
                    jf2 {
                        this["type"] = "card"
                        this["name"] = name
                    }
                }
        return jf2 {
            this["type"] = "card"
            this["name"] = obj.firstText("name")
            this["url"] = obj.getFirstProperty("url")?.let { urlOf(it, base) }
            listOf("latitude", "longitude", "street-address", "locality", "region", "country-name").forEach { property ->
                this[if (property == "country-name") "country" else property] = obj.firstText(property)
            }
        }
    }

    private fun categoryOf(value: Mf2Value): String? =
        when (value) {
            is Mf2Value.Object -> value.value.firstText("url") ?: value.value.firstText("name")
            else -> value.plainTextOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** The URL a property value refers to: a string, a `{value, alt}` image, or a nested object's `url`. */
    fun urlOf(
        value: Mf2Value,
        base: String,
    ): String? {
        val raw =
            when (value) {
                is Mf2Value.Object -> value.value.firstText("url")
                else -> value.plainTextOrNull
            }
        return MicrosubUrls.resolve(base, raw)
    }
}
