package jp.co.crossmap.catalog.graph

import jp.co.crossmap.catalog.Church
import jp.co.crossmap.catalog.ChurchId
import jp.co.crossmap.catalog.DenominationId
import jp.co.crossmap.catalog.EntityRef
import jp.co.crossmap.catalog.MultilingualText
import kotlin.reflect.KClass

interface NodeMetadata<T : Any> {
    val type: KClass<T>
    val label: String
    val idProperty: String
    fun id(value: T): String
    fun toProperties(value: T): Map<String, Any?>
}

class GraphMetadataRegistry(metadata: List<NodeMetadata<*>>) {
    private val byType = metadata.associateBy(NodeMetadata<*>::type)

    init {
        require(byType.size == metadata.size) { "Duplicate graph metadata type registration" }
        metadata.forEach {
            require(IDENTIFIER.matches(it.label)) { "Unsafe Neo4j label: ${it.label}" }
            require(IDENTIFIER.matches(it.idProperty)) { "Unsafe Neo4j ID property: ${it.idProperty}" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> metadata(type: KClass<T>): NodeMetadata<T> =
        byType[type] as? NodeMetadata<T> ?: error("No graph metadata registered for ${type.simpleName}")

    companion object {
        private val IDENTIFIER = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }
}

interface CrossmapGraphMapper {
    fun <T : Any> toNodeProperties(value: T, metadata: NodeMetadata<T>): Map<String, Any?>
}

class DefaultCrossmapGraphMapper : CrossmapGraphMapper {
    override fun <T : Any> toNodeProperties(value: T, metadata: NodeMetadata<T>): Map<String, Any?> {
        val properties = metadata.toProperties(value)
        require(properties[metadata.idProperty] == metadata.id(value)) {
            "${metadata.label}.${metadata.idProperty} must equal the stable domain ID"
        }
        properties.forEach { (name, property) ->
            require(name.matches(Regex("^[A-Za-z][A-Za-z0-9_]*$"))) { "Unsafe Neo4j property name: $name" }
            requireSupportedGraphProperty(name, property)
        }
        return properties.filterValues { it != null }
    }

    private fun requireSupportedGraphProperty(name: String, value: Any?) {
        val supported = value == null || value is String || value is Boolean || value is Int || value is Long ||
            value is Float || value is Double || value is List<*> && value.all {
                it is String || it is Boolean || it is Int || it is Long || it is Float || it is Double
            }
        require(supported) { "Unsupported nested graph property '$name': ${value?.let { it::class.simpleName }}" }
    }
}

object ChurchGraphMetadata : NodeMetadata<Church> {
    override val type = Church::class
    override val label = "Church"
    override val idProperty = "id"
    override fun id(value: Church): String = value.id.value

    override fun toProperties(value: Church): Map<String, Any?> = buildMap {
        put("id", value.id.value)
        put("googlePlaceId", value.googlePlaceId)
        put("primaryName", value.primaryName)
        put("normalizedName", normalizeName(value.primaryName))
        put("englishName", value.englishName)
        put("titleLanguages", value.titleLanguages)
        put("category", value.category)
        put("address", value.address)
        put("latitude", value.location.latitude)
        put("longitude", value.location.longitude)
        put("email", value.email)
        put("updatedAt", value.updatedAt)
        putAll(value.names.toNeo4jNameProperties())
    }
}

fun MultilingualText.toNeo4jNameProperties(): Map<String, String> = values.toSortedMap().mapKeys { (language, _) ->
    "name_${language.replace('-', '_')}"
}

internal fun normalizeName(value: String): String = value.trim().lowercase().replace(Regex("[\\s　]+"), "")

