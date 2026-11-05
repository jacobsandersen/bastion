package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.NestedResponse
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionAnalysis
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.BOOKMARK
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.LIKE
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.MENTION
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.REPLY
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.REPOST
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.RSVP
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2ParseResult
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.firstText
import dev.jacobandersen.microformats2.htmls
import dev.jacobandersen.microformats2.plainTextOrNull
import dev.jacobandersen.microformats2.texts
import java.net.URI

/**
 * Analyzes a parsed source document for webmention purposes: picks the primary
 * object (the first h-entry in document order), classifies the interaction from
 * its `rsvp`/`in-reply-to`/`like-of`/`repost-of`/`bookmark-of` properties
 * (falling back to document-level `rel` attributes when the object declares
 * none) and extracts author/content data.
 *
 * For Salmention it also extracts the response's canonical URL, what it
 * replies to, and the responses it displays nested inside its own entry
 * (`comment` property values and nested h-entry/h-cite children), recursively.
 */
object ReceivedWebmentionAnalyzer {
    /** How deep nested comments are followed below the primary entry. */
    const val MAX_NESTED_DEPTH = 5

    /** The most nested responses taken from a single source. */
    const val MAX_NESTED_RESPONSES = 50

    private val interactionProperties: List<Pair<String, WebmentionInteraction>> =
        listOf(
            "rsvp" to RSVP,
            "in-reply-to" to REPLY,
            "like-of" to LIKE,
            "repost-of" to REPOST,
            "bookmark-of" to BOOKMARK,
        )

    private val interactionRels: List<Pair<String, WebmentionInteraction>> =
        listOf(
            "in-reply-to" to REPLY,
            "like-of" to LIKE,
            "repost-of" to REPOST,
            "bookmark-of" to BOOKMARK,
        )

    private val nestedTypes = setOf("h-entry", "h-cite")

    private val contextProperties = listOf("in-reply-to", "like-of", "repost-of", "bookmark-of")

    /**
     * Analyzes [parseResult]. [sourceUrl] is the webmention source; nested
     * responses equal to it are ignored. Nested responses whose URL matches
     * [exclude] (such as our own replies shown on someone else's page) are
     * skipped with their subtree and do not count toward the nested limit.
     */
    fun analyze(
        parseResult: Mf2ParseResult,
        sourceUrl: String? = null,
        exclude: (String) -> Boolean = { false },
    ): ReceivedWebmentionAnalysis {
        val primary = primaryObject(parseResult)
        if (primary == null) {
            return ReceivedWebmentionAnalysis(interaction = classifyRels(parseResult) ?: MENTION, primary = null)
        }

        val analysis = analyzeObject(primary, classifyProperties(primary) ?: classifyRels(parseResult) ?: MENTION)
        return analysis.copy(nested = nestedResponses(primary, sourceUrl, exclude))
    }

    /**
     * The responses [primary] displays nested inside itself, flattened in
     * document order. Also used to re-read a stored source's microformats.
     */
    fun nestedResponses(
        primary: Mf2Object,
        sourceUrl: String?,
        exclude: (String) -> Boolean,
    ): List<NestedResponse> {
        val primaryUrl = canonicalUrl(primary)
        val seen = listOfNotNull(sourceUrl, primaryUrl).map(::urlKey).toMutableSet()
        val nested = mutableListOf<NestedResponse>()
        collectNested(primary, primaryUrl ?: sourceUrl, 1, seen, exclude, nested)
        return nested
    }

    /** The canonical URL of a microformats object: its first `url`, else its `uid`. */
    fun canonicalUrl(obj: Mf2Object): String? = obj.firstText("url") ?: obj.firstText("uid")

    /**
     * The URLs [obj] replies to. A plain value is used as-is; an embedded
     * reply context (h-cite/h-entry) contributes its canonical URL.
     */
    fun inReplyToOf(obj: Mf2Object): List<String> =
        obj
            .getProperty("in-reply-to")
            .mapNotNull { value ->
                when (value) {
                    is Mf2Value.Object -> canonicalUrl(value.value)
                    else -> value.plainTextOrNull
                }
            }.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    /**
     * A URL keyed for identifying one response: no trailing slash, but the
     * fragment is kept, so `#comment-N` permalinks stay distinct.
     */
    fun urlKey(url: String): String {
        val trimmed = url.trim()
        val page = trimmed.substringBefore('#').trimEnd('/')
        return if ('#' in trimmed) "$page#${trimmed.substringAfter('#')}" else page
    }

    /**
     * The URLs that identify a stored response: its source URL, plus its
     * canonical URL only when that shares the source's origin, so a source
     * cannot claim another site's response by declaring its URL.
     */
    fun identityUrls(
        sourceUrl: String,
        url: String?,
    ): List<String> = listOfNotNull(sourceUrl, url?.takeIf { sameOrigin(sourceUrl, it) })

    private fun sameOrigin(
        a: String,
        b: String,
    ): Boolean {
        val left = originOf(a) ?: return false
        return left == originOf(b)
    }

    private fun originOf(url: String): Triple<String, String, Int>? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port =
            when {
                uri.port != -1 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> -1
            }
        return Triple(scheme, host, port)
    }

    /** A URL normalized to its page: no fragment and no trailing slash. */
    fun normalizeUrl(url: String): String = url.trim().substringBefore('#').trimEnd('/')

    private fun analyzeObject(
        obj: Mf2Object,
        interaction: WebmentionInteraction,
    ): ReceivedWebmentionAnalysis {
        val author = extractAuthor(obj)
        val content = extractContent(obj)
        return ReceivedWebmentionAnalysis(
            interaction = interaction,
            primary = obj,
            authorName = author?.first,
            authorUrl = author?.second,
            authorPhoto = author?.third,
            contentText = content.first,
            contentHtml = content.second,
            url = canonicalUrl(obj),
            inReplyTo = inReplyToOf(obj),
        )
    }

    /**
     * Walks the responses displayed inside [container] depth first, in
     * document order. A nested response that declares no `in-reply-to` is
     * taken to reply to its container. An item without a URL cannot be keyed,
     * so it is skipped but its children are still walked against the same
     * container.
     */
    private fun collectNested(
        container: Mf2Object,
        containerUrl: String?,
        depth: Int,
        seen: MutableSet<String>,
        exclude: (String) -> Boolean,
        out: MutableList<NestedResponse>,
    ) {
        if (depth > MAX_NESTED_DEPTH) return
        for (candidate in nestedCandidates(container)) {
            if (out.size >= MAX_NESTED_RESPONSES) return
            val url = canonicalUrl(candidate)
            if (url == null) {
                collectNested(candidate, containerUrl, depth + 1, seen, exclude, out)
                continue
            }
            if (exclude(url) || !seen.add(urlKey(url))) continue

            // A displayed comment with no explicit response property is a reply to what contains it.
            val analysis = analyzeObject(candidate, classifyProperties(candidate) ?: REPLY)
            val threaded =
                if (analysis.inReplyTo.isEmpty() && containerUrl != null) {
                    analysis.copy(inReplyTo = listOf(containerUrl))
                } else {
                    analysis
                }
            out += NestedResponse(url, threaded)
            collectNested(candidate, url, depth + 1, seen, exclude, out)
        }
    }

    /**
     * `comment` property values plus nested h-entry/h-cite children. A bare
     * child that is the container's own reply/like/repost/bookmark context
     * (the post it responds to) is not a response to it and is left out.
     */
    private fun nestedCandidates(container: Mf2Object): List<Mf2Object> {
        val comments =
            container
                .getProperty("comment")
                .filterIsInstance<Mf2Value.Object>()
                .map { it.value }
        val contextUrls = contextUrlsOf(container)
        val children =
            container.children.orEmpty().filter { child ->
                child.type.any { it in nestedTypes } &&
                    canonicalUrl(child)?.let { urlKey(it) in contextUrls } != true
            }
        return comments + children
    }

    private fun contextUrlsOf(obj: Mf2Object): Set<String> =
        contextProperties
            .flatMap { property ->
                obj.getProperty(property).mapNotNull { value ->
                    when (value) {
                        is Mf2Value.Object -> canonicalUrl(value.value)
                        else -> value.plainTextOrNull
                    }
                }
            }.map(::urlKey)
            .toSet()

    /**
     * The primary object of the source: the first h-entry encountered walking
     * the top-level items and their nested children in document order. Falls
     * back to the first item of any kind.
     */
    private fun primaryObject(parseResult: Mf2ParseResult): Mf2Object? {
        for (item in parseResult.items) {
            val entry = findEntry(item)
            if (entry != null) return entry
        }
        return parseResult.items.firstOrNull()
    }

    private fun findEntry(obj: Mf2Object): Mf2Object? {
        if ("h-entry" in obj.type) return obj
        obj.children?.forEach { child ->
            findEntry(child)?.let { return it }
        }
        return null
    }

    private fun classifyProperties(entry: Mf2Object): WebmentionInteraction? {
        for ((property, interaction) in interactionProperties) {
            if (entry.hasProperty(property)) {
                return interaction
            }
        }
        return null
    }

    private fun classifyRels(parseResult: Mf2ParseResult): WebmentionInteraction? {
        for ((rel, interaction) in interactionRels) {
            if (parseResult.rels.containsKey(rel)) {
                return interaction
            }
        }
        return null
    }

    private fun extractAuthor(entry: Mf2Object): Triple<String?, String?, String?>? {
        val authorValue = entry.getProperty("author").firstOrNull() ?: return null
        return when (authorValue) {
            is Mf2Value.Object -> {
                Triple(
                    authorValue.value.firstText("name"),
                    authorValue.value.firstText("url"),
                    authorValue.value.firstText("photo"),
                )
            }

            // A plain string author is treated as the author's URL.
            is Mf2Value.String -> {
                Triple(null, authorValue.value, null)
            }

            else -> {
                null
            }
        }
    }

    private fun extractContent(entry: Mf2Object): Pair<List<String>, List<String>> {
        val contentTexts = entry.texts("content")
        val contentHtmls = entry.htmls("content")
        if (contentTexts.isNotEmpty() || contentHtmls.isNotEmpty()) {
            return Pair(contentTexts, contentHtmls)
        }
        val summaryTexts = entry.texts("summary")
        return Pair(summaryTexts, emptyList())
    }
}
