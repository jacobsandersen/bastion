package dev.jacobandersen.bastion.post.metadata

import dev.jacobandersen.bastion.post.internal.metadata.PropertiesRegistry
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import org.springframework.stereotype.Service

/**
 * Query side for configured post types and their property metadata. Returns
 * protocol-neutral views; HTTP adapters are responsible for mapping them to a
 * wire shape.
 */
@Service
class PostMetadataService(
    private val catalog: PostTypeCatalog,
    private val propertiesRegistry: PropertiesRegistry,
) {
    fun postTypes(): List<PostTypeView> =
        catalog.types.map { type ->
            PostTypeView(
                type = type.id,
                name = type.name,
                h = type.h,
                properties = catalog.commonProperties + type.properties,
                requiredProperties = type.requiredProperties,
            )
        }

    fun allProperties(): List<PostPropertyView> = propertiesRegistry.allViews()

    /**
     * Returns the property views for a post type, or null when the type is
     * unknown.
     */
    fun propertiesFor(postType: String): List<PostPropertyView>? {
        val definition = catalog.definition(postType) ?: return null
        val names = catalog.commonProperties + definition.properties
        return propertiesRegistry.viewsFor(names)
    }
}
