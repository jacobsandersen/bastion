package dev.jacobandersen.bastion.post.internal.metadata

import dev.jacobandersen.bastion.post.internal.config.PropertiesConfig
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import dev.jacobandersen.bastion.post.metadata.PostPropertyView
import org.springframework.stereotype.Service

/**
 * Access point for the configured property metadata. Validates the config at
 * startup, including that every property referenced by the post type catalog
 * is defined here, and returns protocol-neutral views.
 */
@Service
class PropertiesRegistry(
    private val config: PropertiesConfig,
    catalog: PostTypeCatalog,
) {
    private val byName: Map<String, PropertiesConfig.PropertyDefinition>

    init {
        require(config.properties.isNotEmpty()) { "bastion.properties.properties must not be empty" }
        val names = config.properties.map { it.name }
        require(names.all { it.isNotBlank() }) { "every property needs a non-blank name" }
        require(names.distinct().size == names.size) { "duplicate property names in config" }
        byName = config.properties.associateBy { it.name }

        val missing = catalog.advertisedProperties().distinct().filterNot { it in byName }
        require(missing.isEmpty()) { "post-types reference undefined properties: $missing" }
    }

    fun view(name: String): PostPropertyView? =
        byName[name]?.let { def ->
            PostPropertyView(
                name = def.name,
                displayName = def.displayName,
                hints = def.hints,
                options = def.options,
                defaultValue = def.default,
            )
        }

    fun allViews(): List<PostPropertyView> = config.properties.mapNotNull { view(it.name) }

    fun viewsFor(names: Collection<String>): List<PostPropertyView> = names.mapNotNull { view(it) }
}
