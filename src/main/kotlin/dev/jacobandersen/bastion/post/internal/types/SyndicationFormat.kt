package dev.jacobandersen.bastion.post.internal.types

/**
 * A compiled syndication output template for a post type.
 *
 * Placeholders:
 * - `{name}`: the post title
 * - `{excerpt}`: the truncated body
 * - `{url}`: the canonical post URL
 */
data class SyndicationFormat(
    val format: SyndicationTemplate,
    val fallback: SyndicationTemplate?,
    val overflow: SyndicationTemplate,
) {
    val usesName: Boolean
        get() = format.usesName || fallback?.usesName == true

    companion object {
        fun parse(
            format: String?,
            fallbackFormat: String?,
            overflowFormat: String?,
            context: String,
        ): SyndicationFormat {
            require(!format.isNullOrBlank()) { "$context: syndication format must not be blank" }

            val primary = SyndicationTemplate.parse(format, requireExcerpt = true, context = "$context format")
            val fallback =
                fallbackFormat
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SyndicationTemplate.parse(it, requireExcerpt = true, context = "$context fallback-format") }
            val overflow =
                SyndicationTemplate.parse(
                    overflowFormat?.takeIf { it.isNotBlank() } ?: "{url}",
                    requireExcerpt = false,
                    context = "$context overflow-format",
                )

            require(!primary.usesName || fallback != null) {
                "$context: syndication format uses {name} but no fallback-format is configured"
            }
            require(fallback?.usesName != true) {
                "$context: fallback-format must not use {name}"
            }
            require(!overflow.usesExcerpt) {
                "$context: overflow-format must not use {excerpt}"
            }

            return SyndicationFormat(primary, fallback, overflow)
        }
    }
}

class SyndicationTemplate private constructor(
    private val tokens: List<Token>,
) {
    val usesName: Boolean = tokens.any { it is Token.Name }
    val usesExcerpt: Boolean = tokens.any { it is Token.Excerpt }
    val usesUrl: Boolean = tokens.any { it is Token.Url }

    fun render(
        name: String?,
        excerpt: String?,
        url: String,
    ): String =
        buildString {
            tokens.forEach { token ->
                when (token) {
                    is Token.Literal -> append(token.text)
                    Token.Name -> append(name.orEmpty())
                    Token.Excerpt -> append(excerpt.orEmpty())
                    Token.Url -> append(url)
                }
            }
        }

    private sealed interface Token {
        data class Literal(
            val text: String,
        ) : Token

        data object Name : Token

        data object Excerpt : Token

        data object Url : Token
    }

    companion object {
        fun parse(
            raw: String,
            requireExcerpt: Boolean,
            context: String,
        ): SyndicationTemplate {
            require(raw.isNotBlank()) { "$context must not be blank" }

            val tokens = mutableListOf<Token>()
            val literal = StringBuilder()
            var index = 0

            fun flushLiteral() {
                if (literal.isNotEmpty()) {
                    tokens += Token.Literal(literal.toString())
                    literal.setLength(0)
                }
            }

            while (index < raw.length) {
                val char = raw[index]
                if (char != '{') {
                    literal.append(char)
                    index++
                    continue
                }

                val end = raw.indexOf('}', startIndex = index + 1)
                require(end >= 0) { "$context: unterminated placeholder at index $index" }
                val name = raw.substring(index + 1, end)
                flushLiteral()
                tokens +=
                    when (name) {
                        "name" -> Token.Name
                        "excerpt" -> Token.Excerpt
                        "url" -> Token.Url
                        else -> throw IllegalArgumentException("$context: unknown placeholder {$name}")
                    }
                index = end + 1
            }
            flushLiteral()

            val excerptCount = tokens.count { it is Token.Excerpt }
            require((requireExcerpt && excerptCount == 1) || (!requireExcerpt && excerptCount == 0)) {
                if (requireExcerpt) {
                    "$context must contain exactly one {excerpt} placeholder"
                } else {
                    "$context must not contain {excerpt}"
                }
            }

            return SyndicationTemplate(tokens)
        }
    }
}
