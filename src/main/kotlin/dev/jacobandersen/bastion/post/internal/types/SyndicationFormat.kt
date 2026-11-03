package dev.jacobandersen.bastion.post.internal.types

/**
 * A compiled syndication output template for a post type.
 *
 * Placeholders:
 * - `{excerpt}`: the truncated body, taken from the first usable [ExcerptConfig.sources] entry
 * - `{url}`: the canonical post URL
 * - `{name}`, `{summary}`, `{published}`: the first text value of that mf2 property
 * - `{prop:<property>}`: the first text value of any mf2 property (for example `{prop:in-reply-to}`)
 * - `{tags}`: the post's categories rendered as space-separated hashtags
 *
 * Every placeholder except `{excerpt}` and `{url}` is a *field* and may be missing from a given post. A `format`
 * that uses fields therefore requires a `fallback-format` without fields. Only `{excerpt}` is truncated to the
 * budget; field values are rendered whole, so an oversized field pushes the post to the overflow format.
 */
data class SyndicationFormat(
    val format: SyndicationTemplate,
    val fallback: SyndicationTemplate?,
    val overflow: SyndicationTemplate,
    val excerpt: ExcerptConfig = ExcerptConfig.DEFAULT,
) {
    val usesName: Boolean
        get() = format.usesName || fallback?.usesName == true

    companion object {
        fun parse(
            format: String?,
            fallbackFormat: String?,
            overflowFormat: String?,
            context: String,
            excerpt: ExcerptConfig = ExcerptConfig.DEFAULT,
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

            require(primary.fields.isEmpty() || fallback != null) {
                "$context: syndication format uses optional placeholders " +
                    "${primary.fields.describe()} but no fallback-format is configured"
            }
            require(fallback == null || fallback.fields.isEmpty()) {
                "$context: fallback-format must not use optional placeholders ${fallback?.fields?.describe()}"
            }
            require(!overflow.usesExcerpt) {
                "$context: overflow-format must not use {excerpt}"
            }

            return SyndicationFormat(primary, fallback, overflow, excerpt)
        }

        private fun Set<TemplateField>.describe(): String = joinToString(", ") { "{${it.placeholder}}" }
    }
}

/** An optional, post-derived placeholder. Missing values make the template unusable for that post. */
sealed interface TemplateField {
    /** The placeholder text between the braces. */
    val placeholder: String

    /** The first text value of the mf2 property [key]. */
    data class Property(
        val key: String,
        override val placeholder: String = key,
    ) : TemplateField

    /** The post's categories as hashtags. */
    data object Tags : TemplateField {
        override val placeholder: String = "tags"
    }
}

class SyndicationTemplate private constructor(
    private val tokens: List<Token>,
) {
    /** The distinct optional placeholders this template uses. */
    val fields: Set<TemplateField> = tokens.filterIsInstance<Token.Field>().map { it.field }.toSet()

    /** The mf2 property keys read by this template's fields. */
    val propertyKeys: Set<String> = fields.filterIsInstance<TemplateField.Property>().map { it.key }.toSet()

    val usesName: Boolean = "name" in propertyKeys
    val usesExcerpt: Boolean = tokens.any { it is Token.Excerpt }
    val usesUrl: Boolean = tokens.any { it is Token.Url }

    fun render(
        excerpt: String?,
        url: String,
        resolve: (TemplateField) -> String?,
    ): String =
        buildString {
            tokens.forEach { token ->
                when (token) {
                    is Token.Literal -> append(token.text)
                    is Token.Field -> append(resolve(token.field).orEmpty())
                    Token.Excerpt -> append(excerpt.orEmpty())
                    Token.Url -> append(url)
                }
            }
        }

    private sealed interface Token {
        data class Literal(
            val text: String,
        ) : Token

        data class Field(
            val field: TemplateField,
        ) : Token

        data object Excerpt : Token

        data object Url : Token
    }

    companion object {
        private val PROPERTY_KEY = Regex("[a-z0-9][a-z0-9-]*")

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
                tokens += parsePlaceholder(name, context)
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

        private fun parsePlaceholder(
            name: String,
            context: String,
        ): Token =
            when {
                name == "excerpt" -> {
                    Token.Excerpt
                }

                name == "url" -> {
                    Token.Url
                }

                name == "tags" -> {
                    Token.Field(TemplateField.Tags)
                }

                name == "name" || name == "summary" || name == "published" -> {
                    Token.Field(TemplateField.Property(name))
                }

                name.startsWith("prop:") -> {
                    val key = name.removePrefix("prop:")
                    require(PROPERTY_KEY.matches(key)) { "$context: invalid property name in {$name}" }
                    Token.Field(TemplateField.Property(key, placeholder = name))
                }

                else -> {
                    throw IllegalArgumentException("$context: unknown placeholder {$name}")
                }
            }
    }
}

/** How an over-budget excerpt is shortened. */
enum class ExcerptTruncation {
    /** Cut at the last word boundary when that keeps a reasonable amount of text, otherwise at the grapheme limit. */
    WORD,

    /** Always cut at the grapheme limit. */
    GRAPHEME,
}

/**
 * How `{excerpt}` is built, resolved from the defaults plus a post type's overrides.
 *
 * @property sources mf2 properties tried in order; the first with non-blank text wins. Properties already shown by
 * a field in the chosen template are skipped so the same text is not rendered twice.
 * @property ellipsis appended when the excerpt is truncated; counted against the budget.
 * @property truncate truncation strategy.
 * @property minGraphemes if the budget left for the excerpt is smaller than this, the overflow format is used.
 */
data class ExcerptConfig(
    val sources: List<String>,
    val ellipsis: String,
    val truncate: ExcerptTruncation,
    val minGraphemes: Int,
) {
    companion object {
        val DEFAULT =
            ExcerptConfig(
                sources = listOf("content", "name"),
                ellipsis = "…",
                truncate = ExcerptTruncation.WORD,
                minGraphemes = 20,
            )

        private val PROPERTY_KEY = Regex("[a-z0-9][a-z0-9-]*")

        fun resolve(
            defaults: ExcerptSettings,
            override: ExcerptSettings?,
            context: String,
        ): ExcerptConfig {
            val sources =
                (override?.sources ?: defaults.sources)
                    ?.map { it.trim().lowercase() }
                    ?: DEFAULT.sources
            require(sources.isNotEmpty()) { "$context: excerpt sources must not be empty" }
            sources.forEach {
                require(PROPERTY_KEY.matches(it)) { "$context: invalid excerpt source '$it'" }
            }

            val minGraphemes = override?.minGraphemes ?: defaults.minGraphemes ?: DEFAULT.minGraphemes
            require(minGraphemes >= 0) { "$context: excerpt min-graphemes must not be negative" }

            return ExcerptConfig(
                sources = sources.distinct(),
                ellipsis = override?.ellipsis ?: defaults.ellipsis ?: DEFAULT.ellipsis,
                truncate = override?.truncate ?: defaults.truncate ?: DEFAULT.truncate,
                minGraphemes = minGraphemes,
            )
        }
    }
}
