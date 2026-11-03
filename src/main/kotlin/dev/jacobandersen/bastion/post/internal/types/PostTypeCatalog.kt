package dev.jacobandersen.bastion.post.internal.types

import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.htmlOrNull
import dev.jacobandersen.microformats2.plainTextOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private val logger = KotlinLogging.logger {}

data class PostType(
    val id: String,
    val name: String,
    val h: String,
    val properties: List<String>,
    val requiredProperties: List<String>,
    val syndicationFormat: SyndicationFormat,
)

data class PostTypeDiscovery(
    val type: String?,
    val ruleId: String?,
)

/**
 * The single source of truth for configured post types, their syndication
 * formats, and the ordered discovery rules.
 */
@Service
class PostTypeCatalog(
    config: PostTypesConfig,
    resourceLoader: ResourceLoader,
    environment: ConfigurableEnvironment,
) {
    private val effectiveConfig: PostTypesConfig =
        if (environment.hasPostTypesConfiguration()) {
            config
        } else {
            logger.info { "No external post type configuration found; using the bundled default" }
            loadBundledDefault(resourceLoader)
        }

    val defaultSyndicationFormat: SyndicationFormat =
        SyndicationFormat.parse(
            effectiveConfig.defaults.syndication.format,
            effectiveConfig.defaults.syndication.fallbackFormat,
            effectiveConfig.defaults.syndication.overflowFormat,
            "defaults.syndication",
            ExcerptConfig.resolve(effectiveConfig.defaults.syndication.excerpt, null, "defaults.syndication"),
        )

    val commonProperties: List<String> =
        effectiveConfig.commonProperties.normalizedProperties("bastion.post-types.common-properties")

    val backfill: PostTypesConfig.Backfill = effectiveConfig.backfill

    val types: List<PostType> =
        effectiveConfig.types.map { definition ->
            val id = normalizeTypeId(definition.type)
            val properties = definition.properties.normalizedProperties("post type '$id' properties")
            val requiredProperties = definition.requiredProperties.normalizedProperties("post type '$id' required-properties")
            val syndication =
                if (definition.syndication == null) {
                    defaultSyndicationFormat
                } else {
                    SyndicationFormat.parse(
                        definition.syndication.format ?: effectiveConfig.defaults.syndication.format,
                        definition.syndication.fallbackFormat ?: effectiveConfig.defaults.syndication.fallbackFormat,
                        definition.syndication.overflowFormat ?: effectiveConfig.defaults.syndication.overflowFormat,
                        "post type '$id' syndication",
                        ExcerptConfig.resolve(
                            effectiveConfig.defaults.syndication.excerpt,
                            definition.syndication.excerpt,
                            "post type '$id' syndication",
                        ),
                    )
                }

            PostType(
                id = id,
                name = definition.name.trim(),
                h = normalizeRoot(definition.h, "post type '$id' h"),
                properties = properties,
                requiredProperties = requiredProperties,
                syndicationFormat = syndication,
            )
        }

    private val typesById: Map<String, PostType> = types.associateBy { it.id }

    private val compiledRoots: Map<String, CompiledRoot> = compileRoots()

    val roots: Set<String> = compiledRoots.keys

    val fingerprint: String = computeFingerprint()

    init {
        require(roots.isNotEmpty()) { "bastion.post-types.roots must not be empty" }
        require(types.isNotEmpty()) { "bastion.post-types.types must not be empty" }
        require(types.map { it.id }.distinct().size == types.size) { "duplicate post type ids in configuration" }

        logger.info {
            "Loaded ${types.size} post types across roots ${roots.sorted()} with " +
                "${compiledRoots.values.sumOf { it.rules.size }} discovery rules (fingerprint=$fingerprint)"
        }
        compiledRoots.forEach { (root, compiled) ->
            if (compiled.rules.isEmpty()) {
                logger.warn { "Root '$root' has no discovery rules; posts use fallback '${compiled.fallback ?: "none"}'" }
            }
        }
    }

    /**
     * Discovers the post type of [post] using the rules of its primary root
     * (the first `type` entry, matching the stored `h` column). Returns null
     * when the post has no configured root.
     */
    fun discover(post: Mf2Object): PostTypeDiscovery? {
        val root = compiledRoots[normalizeRoot(post.primaryType(), "post type")] ?: return null

        root.rules.firstOrNull { it.condition.matches(post) }?.let { return PostTypeDiscovery(it.type, it.id) }
        return PostTypeDiscovery(root.fallback, null)
    }

    fun isKnownType(raw: String): Boolean = raw.trim().lowercase(Locale.ROOT) in typesById

    fun definition(raw: String): PostType? = typesById[raw.trim().lowercase(Locale.ROOT)]

    fun syndicationFormatFor(raw: String?): SyndicationFormat =
        raw?.let { typesById[it.trim().lowercase(Locale.ROOT)]?.syndicationFormat } ?: defaultSyndicationFormat

    fun advertisedProperties(): List<String> = (commonProperties + types.flatMap { it.properties + it.requiredProperties }).distinct()

    fun advertisedPropertiesFor(type: String): List<String> {
        val definition = definition(type) ?: return emptyList()
        return (commonProperties + definition.properties + definition.requiredProperties).distinct()
    }

    private fun compileRoots(): Map<String, CompiledRoot> {
        val compiled = linkedMapOf<String, CompiledRoot>()
        effectiveConfig.roots.forEach { (rawRoot, rootConfig) ->
            val root = normalizeRoot(rawRoot, "bastion.post-types.roots key")
            require(root !in compiled) { "duplicate post type root '$root'" }

            val fallback = rootConfig.fallback?.let(::normalizeTypeId)
            if (fallback != null) {
                val definition =
                    typesById[fallback]
                        ?: throw IllegalArgumentException("root '$root' fallback targets unknown post type '$fallback'")
                require(definition.h == root) {
                    "root '$root' fallback '$fallback' belongs to root '${definition.h}'"
                }
            }

            val ruleIds = mutableSetOf<String>()
            val rules =
                rootConfig.rules.map { rule ->
                    val id = rule.id.trim()
                    require(id.isNotEmpty()) { "root '$root': discovery rule id must not be blank" }
                    require(ruleIds.add(id)) { "root '$root': duplicate discovery rule id '$id'" }

                    val typeId = normalizeTypeId(rule.type)
                    val definition =
                        typesById[typeId]
                            ?: throw IllegalArgumentException("discovery rule '$id' targets unknown post type '$typeId'")
                    require(definition.h == root) {
                        "discovery rule '$id' in root '$root' targets post type '$typeId' which belongs to root '${definition.h}'"
                    }
                    val advertised = advertisedPropertiesFor(typeId).toSet()
                    CompiledRule(id, typeId, compileCondition(rule.match, id, advertised))
                }
            compiled[root] = CompiledRoot(fallback, rules)
        }
        return compiled
    }

    private fun compileCondition(
        condition: PostTypesConfig.RuleCondition,
        ruleId: String,
        advertisedProperties: Set<String>,
    ): CompiledCondition {
        val forms =
            listOfNotNull(
                condition.property?.let { "property" },
                condition.all?.let { "all" },
                condition.any?.let { "any" },
                condition.not?.let { "not" },
                condition.namePrefixOf?.let { "name-prefix-of" },
            )
        require(forms.size == 1) {
            "discovery rule '$ruleId': condition must define exactly one of " +
                "property, all, any, not, name-prefix-of"
        }

        val form = forms.single()
        if (form != "property") {
            require(condition.value == null) {
                "discovery rule '$ruleId': value is only valid with property"
            }
        }

        return when (form) {
            "property" -> {
                val property = condition.property!!.trim()
                require(property.isNotEmpty()) { "discovery rule '$ruleId': property must not be blank" }
                require(property in advertisedProperties) {
                    "discovery rule '$ruleId' uses property '$property' which is not advertised by its post type"
                }
                PropertyCondition(property, compileValue(condition.value, ruleId))
            }

            "all" -> {
                val conditions = condition.all!!
                require(conditions.isNotEmpty()) { "discovery rule '$ruleId': all must not be empty" }
                AllCondition(conditions.map { compileCondition(it, ruleId, advertisedProperties) })
            }

            "any" -> {
                val conditions = condition.any!!
                require(conditions.isNotEmpty()) { "discovery rule '$ruleId': any must not be empty" }
                AnyCondition(conditions.map { compileCondition(it, ruleId, advertisedProperties) })
            }

            "not" -> {
                NotCondition(compileCondition(condition.not!!.asCondition(), ruleId, advertisedProperties))
            }

            "name-prefix-of" -> {
                val properties = condition.namePrefixOf!!
                require(properties.isNotEmpty()) { "discovery rule '$ruleId': name-prefix-of must not be empty" }
                val normalized = properties.map { it.trim() }
                require(normalized.all { it.isNotEmpty() }) {
                    "discovery rule '$ruleId': name-prefix-of properties must not be blank"
                }
                require(normalized.all { it in advertisedProperties }) {
                    "discovery rule '$ruleId' uses a name-prefix-of property which is not advertised by its post type"
                }
                NamePrefixOfCondition(normalized)
            }

            else -> {
                error("unreachable")
            }
        }
    }

    private fun compileValue(
        value: PostTypesConfig.ValueCondition?,
        ruleId: String,
    ): ValueMatcher {
        if (value == null) return ValueMatcher.Text

        val text = value.text == true
        val url = value.url == true
        val oneOf =
            value.oneOf
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
        val objectHas = value.objectHas?.trim()?.takeIf { it.isNotEmpty() }
        val matchers = listOf(text, url, oneOf != null, objectHas != null).count { it }
        require(matchers == 1) {
            "discovery rule '$ruleId': value must define exactly one of text, url, one-of, object-has"
        }

        return when {
            text -> ValueMatcher.Text
            url -> ValueMatcher.Url
            oneOf != null -> ValueMatcher.OneOf(oneOf.map { it.lowercase(Locale.ROOT) }.toSet())
            else -> ValueMatcher.ObjectHas(requireNotNull(objectHas))
        }
    }

    private fun computeFingerprint(): String {
        val canonical =
            buildString {
                compiledRoots.toSortedMap().forEach { (root, compiled) ->
                    append("root=")
                        .append(root)
                        .append('|')
                        .append(compiled.fallback ?: "")
                        .append('\n')
                    compiled.rules.forEach {
                        append("rule=")
                            .append(root)
                            .append('|')
                            .append(it.id)
                            .append('|')
                            .append(it.type)
                            .append('|')
                            .append(it.condition.canonical())
                            .append('\n')
                    }
                }
            }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadBundledDefault(resourceLoader: ResourceLoader): PostTypesConfig {
        val resource = resourceLoader.getResource("classpath:post-types-bundled.yaml")
        check(resource.exists()) { "Bundled post type configuration is missing" }

        val sources = MutablePropertySources()
        YamlPropertySourceLoader().load("post-types-bundled", resource).forEach { sources.addLast(it) }
        val binder = Binder(ConfigurationPropertySources.from(sources))
        return binder
            .bind("bastion.post-types", Bindable.of(PostTypesConfig::class.java))
            .orElseThrow { IllegalStateException("Could not bind bundled post type configuration") }
    }

    private data class CompiledRoot(
        val fallback: String?,
        val rules: List<CompiledRule>,
    )

    private data class CompiledRule(
        val id: String,
        val type: String,
        val condition: CompiledCondition,
    )

    private sealed interface CompiledCondition {
        fun matches(post: Mf2Object): Boolean

        fun canonical(): String
    }

    private data class PropertyCondition(
        val name: String,
        val matcher: ValueMatcher,
    ) : CompiledCondition {
        override fun matches(post: Mf2Object): Boolean = post.getProperty(name).any { matcher.matches(it) }

        override fun canonical(): String = "property:$name:${matcher.canonical()}"
    }

    private data class AllCondition(
        val conditions: List<CompiledCondition>,
    ) : CompiledCondition {
        override fun matches(post: Mf2Object): Boolean = conditions.all { it.matches(post) }

        override fun canonical(): String = "all(${conditions.joinToString(",") { it.canonical() }})"
    }

    private data class AnyCondition(
        val conditions: List<CompiledCondition>,
    ) : CompiledCondition {
        override fun matches(post: Mf2Object): Boolean = conditions.any { it.matches(post) }

        override fun canonical(): String = "any(${conditions.joinToString(",") { it.canonical() }})"
    }

    private data class NotCondition(
        val condition: CompiledCondition,
    ) : CompiledCondition {
        override fun matches(post: Mf2Object): Boolean = !condition.matches(post)

        override fun canonical(): String = "not(${condition.canonical()})"
    }

    private data class NamePrefixOfCondition(
        val properties: List<String>,
    ) : CompiledCondition {
        override fun matches(post: Mf2Object): Boolean {
            val content =
                properties
                    .firstNotNullOfOrNull { post.firstTextIncludingHtml(it)?.normalized() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return false
            val name =
                post
                    .firstTextIncludingHtml("name")
                    ?.normalized()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return false
            return content.startsWith(name)
        }

        override fun canonical(): String = "name-prefix-of(${properties.joinToString(",")})"
    }

    private sealed interface ValueMatcher {
        fun matches(value: Mf2Value): Boolean

        fun canonical(): String

        data object Text : ValueMatcher {
            override fun matches(value: Mf2Value): Boolean = value.textOrNull() != null

            override fun canonical(): String = "text"
        }

        data object Url : ValueMatcher {
            override fun matches(value: Mf2Value): Boolean = value.urlOrNull()?.let(::isAbsoluteHttpUrl) == true

            override fun canonical(): String = "url"
        }

        data class OneOf(
            val values: Set<String>,
        ) : ValueMatcher {
            override fun matches(value: Mf2Value): Boolean = value.textOrNull()?.normalized()?.lowercase(Locale.ROOT) in values

            override fun canonical(): String = "one-of(${values.sorted().joinToString(",")})"
        }

        data class ObjectHas(
            val property: String,
        ) : ValueMatcher {
            override fun matches(value: Mf2Value): Boolean = (value as? Mf2Value.Object)?.value?.firstTextIncludingHtml(property) != null

            override fun canonical(): String = "object-has($property)"
        }
    }
}

/**
 * Whether any `bastion.post-types.*` property is present. This distinguishes
 * an absent external configuration (use the bundled default) from a present
 * but incomplete one (fail fast instead of silently merging with the default).
 */
private fun ConfigurableEnvironment.hasPostTypesConfiguration(): Boolean =
    propertySources.any { source ->
        source is EnumerablePropertySource<*> &&
            source.propertyNames.any { it.startsWith("bastion.post-types.") }
    }

private fun normalizeTypeId(raw: String): String {
    val value = raw.trim().lowercase(Locale.ROOT)
    require(value.matches(Regex("^[a-z][a-z0-9-]*$"))) { "invalid post type id '$raw'" }
    return value
}

private fun List<String>.normalizedProperties(context: String): List<String> =
    map { it.trim() }.also { values ->
        require(values.none { it.isEmpty() }) { "$context must not contain blank entries" }
    }

private fun normalizeRoot(
    raw: String,
    context: String,
): String {
    val value = raw.trim().lowercase(Locale.ROOT).removePrefix("h-")
    require(value.isNotEmpty()) { "$context must not be blank" }
    return value
}

private fun Mf2Object.firstTextIncludingHtml(key: String): String? =
    getProperty(key).firstNotNullOfOrNull { it.textOrNull() }?.takeIf { it.isNotBlank() }

private fun Mf2Value.textOrNull(): String? = plainTextOrNull?.takeIf { it.isNotBlank() } ?: htmlOrNull?.takeIf { it.isNotBlank() }

private fun Mf2Value.urlOrNull(): String? =
    when (this) {
        is Mf2Value.String -> {
            value.trim().takeIf { it.isNotEmpty() }
        }

        is Mf2Value.Json -> {
            value
                .get("value")
                ?.takeIf(JsonNode::isString)
                ?.asString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: value
                    .get("url")
                    ?.takeIf(JsonNode::isString)
                    ?.asString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
        }

        is Mf2Value.Object -> {
            value.firstTextIncludingHtml("url")
        }

        else -> {
            null
        }
    }

private fun isAbsoluteHttpUrl(raw: String): Boolean =
    try {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

private fun String.normalized(): String = replace(Regex("\\s+"), " ").trim()
