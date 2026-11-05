package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.bastion.microsub.internal.jf2.HtmlSanitizer
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** JSON Feed 1.0 and 1.1 (https://jsonfeed.org/version/1.1). */
object JsonFeedParser {
    fun isJsonFeed(root: JsonNode): Boolean = root.path("version").asString("").startsWith("https://jsonfeed.org/version/")

    fun parse(
        mapper: ObjectMapper,
        body: ByteArray,
        url: String,
        maxItems: Int,
    ): ParsedFeed? {
        val root = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        if (!isJsonFeed(root)) return null

        val home = text(root, "home_page_url")?.let { MicrosubUrls.resolve(url, it) } ?: url
        val feedAuthor =
            authorOf(root, home) ?: jf2 {
                this["type"] = "card"
                this["name"] = text(root, "title")
                this["url"] = home
                this["photo"] = (text(root, "icon") ?: text(root, "favicon"))?.let { MicrosubUrls.resolve(url, it) }
            }

        return ParsedFeed(
            format = FeedFormat.JSON,
            url = url,
            name = text(root, "title"),
            photo = (text(root, "icon") ?: text(root, "favicon"))?.let { MicrosubUrls.resolve(url, it) },
            description = text(root, "description"),
            items =
                root
                    .path("items")
                    .take(maxItems)
                    .mapNotNull { item(it, home, feedAuthor) }
                    .map(Jf2Item::of),
            hubs = root.path("hubs").mapNotNull { hub -> text(hub, "url")?.let { MicrosubUrls.resolve(url, it) } },
            self = text(root, "feed_url")?.let { MicrosubUrls.resolve(url, it) },
        )
    }

    private fun item(
        node: JsonNode,
        base: String,
        feedAuthor: Map<String, Any>,
    ): Map<String, Any>? {
        val url = (text(node, "url") ?: text(node, "external_url"))?.let { MicrosubUrls.resolve(base, it) }
        val content = HtmlSanitizer.content(text(node, "content_html"), text(node, "content_text"), url ?: base)
        val attachments =
            node.path("attachments").mapNotNull { a ->
                text(a, "url")?.let { MicrosubUrls.resolve(base, it) }?.let {
                    text(a, "mime_type").orEmpty() to
                        it
                }
            }
        val data =
            jf2 {
                this["type"] = "entry"
                this["url"] = url
                this["uid"] = text(node, "id")
                this["name"] = text(node, "title")
                this["summary"] = text(node, "summary")
                this["published"] = (text(node, "date_published") ?: text(node, "date_modified"))?.let(::iso)
                this["updated"] = text(node, "date_modified")?.let(::iso)
                this["content"] = content
                this["author"] = authorOf(node, base) ?: feedAuthor.takeIf { it.size > 1 }
                this["category"] = node.path("tags").mapNotNull { it.asString(null)?.trim()?.takeIf(String::isNotEmpty) }
                this["photo"] =
                    listOfNotNull(text(node, "image")?.let { MicrosubUrls.resolve(base, it) }) +
                    attachments.filter { it.first.startsWith("image/") }.map { it.second }
                this["video"] = attachments.filter { it.first.startsWith("video/") }.map { it.second }
                this["audio"] = attachments.filter { it.first.startsWith("audio/") }.map { it.second }
            }
        return data.takeIf { url != null || content != null }
    }

    private fun authorOf(
        node: JsonNode,
        base: String,
    ): Map<String, Any>? {
        val author = node.path("authors").firstOrNull() ?: node.get("author") ?: return null
        return jf2 {
            this["type"] = "card"
            this["name"] = text(author, "name")
            this["url"] = text(author, "url")?.let { MicrosubUrls.resolve(base, it) }
            this["photo"] = text(author, "avatar")?.let { MicrosubUrls.resolve(base, it) }
        }.takeIf { it.size > 1 }
    }

    private fun iso(value: String): String = Jf2Item.parseInstant(value)?.let(Jf2Item::formatInstant) ?: value

    private fun text(
        node: JsonNode,
        field: String,
    ): String? =
        node
            .get(field)
            ?.takeIf { it.isString }
            ?.asString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
