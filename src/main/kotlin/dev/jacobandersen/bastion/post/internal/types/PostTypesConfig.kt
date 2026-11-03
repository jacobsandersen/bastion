package dev.jacobandersen.bastion.post.internal.types

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Declarative post type catalog and discovery rules, bound from the external
 * `post-types.yaml` or the bundled default. An external configuration fully
 * replaces the bundled default.
 */
@ConfigurationProperties(prefix = "bastion.post-types")
data class PostTypesConfig(
    val roots: Map<String, Root> = emptyMap(),
    val commonProperties: List<String> = emptyList(),
    val defaults: Defaults = Defaults(),
    val types: List<PostTypeDefinition> = emptyList(),
    val backfill: Backfill = Backfill(),
) {
    data class Defaults(
        val syndication: SyndicationFormatConfig = SyndicationFormatConfig(),
    )

    data class PostTypeDefinition(
        val type: String = "",
        val name: String = "",
        val h: String = "entry",
        val properties: List<String> = emptyList(),
        val requiredProperties: List<String> = emptyList(),
        val syndication: SyndicationFormatConfig? = null,
    )

    /** Discovery configuration for one mf2 root (for example `entry` for h-entry). */
    data class Root(
        val fallback: String? = null,
        val rules: List<DiscoveryRule> = emptyList(),
    )

    data class DiscoveryRule(
        val id: String = "",
        val type: String = "",
        val match: RuleCondition = RuleCondition(),
    )

    data class RuleCondition(
        val property: String? = null,
        val value: ValueCondition? = null,
        val all: List<RuleCondition>? = null,
        val any: List<RuleCondition>? = null,
        val not: NegatedCondition? = null,
        val namePrefixOf: List<String>? = null,
    )

    /**
     * A negated condition. This is a separate type from [RuleCondition] because
     * Spring's binder does not bind a property whose type is the same type that
     * declares it (a direct self-reference), and `not` would otherwise be
     * silently ignored.
     */
    data class NegatedCondition(
        val property: String? = null,
        val value: ValueCondition? = null,
        val all: List<RuleCondition>? = null,
        val any: List<RuleCondition>? = null,
        val namePrefixOf: List<String>? = null,
    ) {
        fun asCondition(): RuleCondition = RuleCondition(property, value, all, any, null, namePrefixOf)
    }

    data class ValueCondition(
        val text: Boolean? = null,
        val url: Boolean? = null,
        val oneOf: List<String>? = null,
        val objectHas: String? = null,
    )

    data class Backfill(
        val batchSize: Int = 500,
        val dryRun: Boolean = false,
        val leaseMinutes: Long = 30,
    )
}

data class SyndicationFormatConfig(
    val format: String? = null,
    val fallbackFormat: String? = null,
    val overflowFormat: String? = null,
    val excerpt: ExcerptSettings = ExcerptSettings(),
)

/** Raw `excerpt` settings; unset fields inherit from the defaults, then [ExcerptConfig.DEFAULT]. */
data class ExcerptSettings(
    val sources: List<String>? = null,
    val ellipsis: String? = null,
    val truncate: ExcerptTruncation? = null,
    val minGraphemes: Int? = null,
)
