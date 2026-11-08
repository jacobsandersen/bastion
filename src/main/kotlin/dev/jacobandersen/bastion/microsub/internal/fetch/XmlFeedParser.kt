package dev.jacobandersen.bastion.microsub.internal.fetch

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import dev.jacobandersen.bastion.microsub.internal.jf2.HtmlSanitizer
import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item
import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.microsub.internal.jf2.jf2
import org.jdom2.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream

/**
 * RSS and Atom via Rome. Rome rejects DOCTYPE declarations by default, which
 * also blocks XML external entity tricks in untrusted feeds.
 */
object XmlFeedParser {
    private const val ATOM_NS = "http://www.w3.org/2005/Atom"

    fun parse(
        body: ByteArray,
        url: String,
        maxItems: Int,
    ): ParsedFeed {
        val feed = SyndFeedInput().build(InputSource(ByteArrayInputStream(body)))
        val home = feed.link?.let { MicrosubUrls.resolve(url, it) } ?: url
        val feedAuthor =
            jf2 {
                this["type"] = "card"
                this["name"] = feed.title?.let(HtmlSanitizer::text)
                this["url"] = home
                this["photo"] = feed.image?.url?.let { MicrosubUrls.resolve(url, it) }
            }

        val links = atomLinks(feed)
        return ParsedFeed(
            format = if (feed.feedType.startsWith("atom")) FeedFormat.ATOM else FeedFormat.RSS,
            url = url,
            name = feed.title?.let(HtmlSanitizer::text),
            photo = feed.image?.url?.let { MicrosubUrls.resolve(url, it) },
            description = feed.description?.let(HtmlSanitizer::text),
            items =
                feed.entries
                    .take(maxItems)
                    .mapNotNull { entry(it, home, feedAuthor) }
                    .map(Jf2Item::of),
            hubs = links.filter { it.first == "hub" }.mapNotNull { MicrosubUrls.resolve(url, it.second) },
            self = links.firstOrNull { it.first == "self" }?.let { MicrosubUrls.resolve(url, it.second) },
        )
    }

    /** Atom `link` elements, both native (Atom) and foreign markup (`atom:link` in RSS). */
    private fun atomLinks(feed: SyndFeed): List<Pair<String, String>> {
        val native = feed.links.orEmpty().mapNotNull { link -> link.rel?.let { it to link.href } }
        val foreign =
            (feed.foreignMarkup.orEmpty() as List<Element>)
                .filter { it.name == "link" && it.namespaceURI == ATOM_NS }
                .mapNotNull { element ->
                    val rel = element.getAttributeValue("rel") ?: return@mapNotNull null
                    val href = element.getAttributeValue("href") ?: return@mapNotNull null
                    rel to href
                }
        return (native + foreign).filter { it.second.isNotBlank() }
    }

    private fun entry(
        entry: SyndEntry,
        base: String,
        feedAuthor: Map<String, Any>,
    ): Map<String, Any>? {
        val url =
            (
                entry.link ?: entry.links
                    .orEmpty()
                    .firstOrNull {
                        it.rel == null || it.rel == "alternate"
                    }?.href
            )?.let { MicrosubUrls.resolve(base, it) }
        val body = entry.contents.orEmpty().firstOrNull { !it.value.isNullOrBlank() }
        val description = entry.description?.value?.takeIf { it.isNotBlank() }
        val html = body?.value ?: description
        val isText = (body ?: entry.description)?.type?.lowercase()?.let { it == "text" || it == "text/plain" } ?: false
        val content = if (isText) HtmlSanitizer.content(null, html, url ?: base) else HtmlSanitizer.content(html, null, url ?: base)
        val contentText = content?.get("text") as? String
        val title = entry.title?.let(HtmlSanitizer::text)?.takeIf { it.isNotBlank() }
        val name =
            title?.takeUnless {
                contentText != null &&
                    squash(contentText).startsWith(squash(it).removeSuffix("...").removeSuffix("…"))
            }

        val person = entry.authors.orEmpty().firstOrNull()
        val author =
            if (person != null && (!person.name.isNullOrBlank() || !person.uri.isNullOrBlank())) {
                jf2 {
                    this["type"] = "card"
                    this["name"] = person.name
                    this["url"] = person.uri?.let { MicrosubUrls.resolve(base, it) }
                }
            } else if (!entry.author.isNullOrBlank()) {
                jf2 {
                    this["type"] = "card"
                    this["name"] = entry.author
                    this["url"] = feedAuthor["url"]
                    this["photo"] = feedAuthor["photo"]
                }
            } else {
                feedAuthor
            }

        val enclosures =
            entry.enclosures.orEmpty().mapNotNull { e ->
                MicrosubUrls.resolve(base, e.url)?.let {
                    (e.type.orEmpty().lowercase()) to
                        it
                }
            }
        val data =
            jf2 {
                this["type"] = "entry"
                this["url"] = url
                this["uid"] = entry.uri?.takeIf { it.isNotBlank() }
                this["name"] = name
                this["summary"] =
                    if (body != null && description != null) HtmlSanitizer.text(description).takeIf { it != contentText } else null
                this["published"] = (entry.publishedDate ?: entry.updatedDate)?.toInstant()?.let(Jf2Item::formatInstant)
                this["updated"] = entry.updatedDate?.toInstant()?.let(Jf2Item::formatInstant)
                this["content"] = content
                this["author"] = author.takeIf { it.size > 1 }
                this["category"] =
                    entry.categories
                        .orEmpty()
                        .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                        .distinct()
                this["photo"] = enclosures.filter { it.first.startsWith("image/") }.map { it.second }
                this["video"] = enclosures.filter { it.first.startsWith("video/") }.map { it.second }
                this["audio"] = enclosures.filter { it.first.startsWith("audio/") }.map { it.second }
            }
        return data.takeIf { url != null || content != null || name != null }
    }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()
}
