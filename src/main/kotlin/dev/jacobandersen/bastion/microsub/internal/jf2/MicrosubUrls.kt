package dev.jacobandersen.bastion.microsub.internal.jf2

import java.net.URI

object MicrosubUrls {
    /**
     * A comparable form of an http(s) URL: lowercase scheme and host, no
     * default port, no fragment, and `/` for an empty path. Returns null for
     * anything that is not an absolute http(s) URL.
     */
    fun normalize(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host${port?.let { ":$it" }.orEmpty()}$path$query"
    }

    /**
     * Turns what a user typed into a URL: absolute http(s) URLs pass through,
     * a bare host or host/path gets `https://` and must have a dotted host.
     * Returns null for anything else.
     */
    fun fromUserInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        if (trimmed.contains("://")) return normalize(trimmed)
        // Without a scheme, require a dotted host so a bare search word is not taken for a hostname.
        val normalized = normalize("https://$trimmed") ?: return null
        return normalized.takeIf { URI(it).host.contains('.') }
    }

    fun resolve(
        base: String,
        reference: String?,
    ): String? {
        val ref = reference?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { URI(base).resolve(ref).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
