package dev.jacobandersen.bastion.webmention.internal.data.domain

import dev.jacobandersen.microformats2.Mf2Object

/**
 * The result of analyzing a received webmention's source document: the
 * detected interaction type plus the normalized author and content extracted
 * from its primary microformats object.
 *
 * [url] is the response's canonical `u-url` (it may differ from the
 * webmention source URL) and [inReplyTo] the URLs it replies to, used to
 * thread replies to other responses. [nested] holds the responses the source
 * displays inside its own entry (Salmention), flattened in document order.
 */
data class ReceivedWebmentionAnalysis(
    val interaction: WebmentionInteraction,
    val primary: Mf2Object?,
    val authorName: String? = null,
    val authorUrl: String? = null,
    val authorPhoto: String? = null,
    val contentText: List<String> = emptyList(),
    val contentHtml: List<String> = emptyList(),
    val url: String? = null,
    val inReplyTo: List<String> = emptyList(),
    val nested: List<NestedResponse> = emptyList(),
)

/**
 * A response displayed nested inside a webmention source, keyed by its
 * canonical [url]. Its [analysis] never has nested responses of its own: the
 * whole subtree is flattened onto the source that displays it.
 */
data class NestedResponse(
    val url: String,
    val analysis: ReceivedWebmentionAnalysis,
)
