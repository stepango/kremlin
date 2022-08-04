package com.stepango.kremlin

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.`__` as c
import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.`__`.elementMap
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Tree
import org.apache.tinkerpop.gremlin.structure.Element
import org.apache.tinkerpop.gremlin.structure.Vertex
import java.time.Instant.now

val logger = KotlinLogging.logger {}

typealias GraphMap = Map<String, Any?>

/**
 * Wrapping addVertex to support reified types for recursive calls
 */
inline fun <reified T : Model> GraphTraversalSource.addVertex(obj: T): Vertex = addVertex(T::class, obj)

@Suppress("UNCHECKED_CAST")
fun <T : Model> GraphTraversalSource.updateVertex(
    clazz: KClass<T>,
    obj: T
): Vertex {
    if (obj.id.isEmpty()) throw IllegalArgumentException("Object should have valid id")

    val (primitives, serializable, _) = splitProperties(clazz)

    var traversal: GraphTraversal<*, Vertex> = V(obj.id)

    primitives // id field is created by DB internally, so we are not saving it
        .filter { it.name != Model::id.name }
        .forEach { prop -> traversal = updateProperty(traversal, prop, obj) }
    // Save primitive properties of root vertex
    val internalVertexTraversal = traversal.next()

    serializable.forEach { prop ->
        val kClass = prop.javaGetter!!.returnType.kotlin as KClass<Model>
        updateVertex(kClass, prop.get(obj).cast(kClass))
    }

    return internalVertexTraversal
}

@Suppress("UNCHECKED_CAST")
fun <T : Model> GraphTraversalSource.addVertex(
    clazz: KClass<T>,
    obj: Any
): Vertex {
    val label = clazz.qualifiedName
    logger.debug { "Adding vertex with label: $label" }

    obj as T

    val (primitives, serializable, lists) = splitProperties(clazz)

    var traversal: GraphTraversal<*, Vertex> = addV(label)

    primitives
        // id field is created by DB internally, so we are not saving it
        .filter { it.name != Model::id.name }
        .forEach { prop -> traversal = populateProperty(traversal, prop, obj) }
    // Save primitive properties of root vertex
    val internalVertexTraversal = traversal.next()

    serializable.forEach { prop ->
        val edgeLabel = prop.name
        val kClass = prop.javaGetter!!.returnType.kotlin
        val internalVertex = addVertex(kClass as KClass<Model>, prop.get(obj).cast(kClass))
        addEdge(edgeLabel, internalVertexTraversal, internalVertex)
    }

    lists.forEach { prop ->
        val edgeLabel = prop.name
        val list = prop.get(obj) as List<Any>
        list.forEach { obj ->
            val internalVertex = addVertex(obj::class as KClass<Model>, obj)
            addEdge(edgeLabel, internalVertexTraversal, internalVertex)
        }
    }

    return internalVertexTraversal
}

fun <T> populateProperty(
    traversal: GraphTraversal<*, Vertex>,
    prop: KProperty1<T, *>,
    obj: T
): GraphTraversal<out Any, Vertex> {
    val name = prop.name
    val value = if (name == ModelUpdatable::updatedAt.name || name == ModelUpdatable::createdAt.name) {
        time()
    } else {
        prop.get(obj)
    }
    return traversal.property(name, value ?: "null_value")
}

fun <T : Model> updateProperty(
    traversal: GraphTraversal<*, Vertex>,
    prop: KProperty1<T, *>,
    obj: T
): GraphTraversal<*, Vertex> {
    val name = prop.name
    val value = if (name == ModelUpdatable::updatedAt.name) {
        time()
    } else {
        prop.get(obj)
    }
    return traversal.property(name, value ?: "null_value")
}

fun GraphTraversalSource.addEdge(
    edgeLabel: String,
    fromVertex: Vertex,
    toVertex: Vertex
) {
    addE(edgeLabel).from(fromVertex).toVertex(toVertex).iterate()
}

fun <T : Any> Any?.cast(clazz: KClass<out T>): T = clazz.javaObjectType.cast(this)

data class Properties<T>(
    val primitives: List<KProperty1<T, *>>,
    val serializable: List<KProperty1<T, Model>>,
    val lists: List<KProperty1<T, List<*>>>
)

@Suppress("UNCHECKED_CAST")
fun <T : Any> splitProperties(
    clazz: KClass<T>
): Properties<T> {
    val primitives = ArrayList<KProperty1<T, *>>()
    val serializable = ArrayList<KProperty1<T, Model>>()
    val lists = ArrayList<KProperty1<T, List<*>>>()
    clazz.memberProperties.forEach {
        val type = it.javaGetter!!.returnType
        if (isSerializable(type)) {
            serializable.add(it as KProperty1<T, Model>)
        } else if (type.isAssignableFrom(List::class.java)) {
            lists.add(it as KProperty1<T, List<*>>)
        } else {
            primitives.add(it)
        }
    }
    return Properties(
        primitives,
        serializable,
        lists
    )
}

fun isSerializable(it: Class<*>) =
    it.annotations.any { it is Serializable }

private fun <S, E> GraphTraversal<S, E>.toVertex(toVertex: Vertex): GraphTraversal<S, E> =
    TraversalUtils.toVertex(this, toVertex)

/**
 * filterArgs: gremlin creates 'id' field for every object, we don't map it to data classes
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> mapToObject(map: GraphMap, clazz: KClass<T>): T {
    // Make sure constructor parameters list matches with map args
    val constructor = clazz.constructors.first { constructor ->
        val args = constructor.parameters.map { it.name }
        map.keys.containsAll(args)
    }

    //Map constructor parameters to map values
    val args = constructor
        .parameters
        .associateWith { map[it.name] }
        .entries
        .fold(mutableMapOf<KParameter, Any?>()) { result, (param, value) ->
            result[param] = when (value) {
                is Map<*, *> -> mapToObject(value as Map<String, Any>, param.type.classifier as KClass<*>)
                is List<*> -> value.map { mapToObject(it as Map<String, Any>, classForMap(it)) }
                else -> value
            }
            result
        }

    //create object by calling constructor
    return constructor.callBy(args)
}

private fun classForMap(it: Map<String, Any>) =
    Class.forName(it["label"].toString()).kotlin

inline fun <reified T : Any> GraphTraversalSource.readVertex(propertyKey: String, value: String): T {
    val label = T::class.qualifiedName
    val map = V()
        .hasLabel(label)
        .has(propertyKey, value)
        .inflateEdges()
        .next()
        .asMaps()[0]
    @Suppress("UNCHECKED_CAST")
    return mapToObject(map, T::class)
}

inline fun <reified T : Any> GraphTraversalSource.readVertex(id: String): T {
    val map = V(id).let {
        if (splitProperties(T::class).serializable.isNotEmpty()) {
            it.inflateEdges()
        } else {
            it.tree().by(elementMap<Element, Any>())
        }
    }.next()
        .asMaps()[0]
    @Suppress("UNCHECKED_CAST")
    return mapToObject(map, T::class)
}

inline fun <reified From : Any, reified To : Any> GraphTraversalSource.readObjectDependencies(
    propertyKey: String,
    value: String,
    edgeLabel: String
): List<To> {
    return V()
        .hasLabel(From::class.qualifiedName)
        .has(propertyKey, value)
        .outE(edgeLabel)
        .outV()
        .inflateEdges()
        .next()
        .asMaps()
        .map { mapToObject(it, To::class) }
}

inline fun <reified T : Model> GraphTraversalSource.readObject(id: String): T {
    val map = readObjectAsMap<T>(id)
    return mapToObject(map, T::class)
}

inline fun <reified T : Any> GraphTraversalSource.readObject(propertyKey: String, value: Any): T {
    if (propertyKey == "id") throw IllegalArgumentException(
        "This method can't be used for query by id\n" +
                "Please use function with following signature instead - GraphTraversalSource.readObject(id: Long)"
    )
    val map = readObjectAsMap<T>(mapOf(propertyKey to value))
    return mapToObject(map, T::class)
}

inline fun <reified T : Any> GraphTraversalSource.readObject(properties: Map<String, Any>): T {
    if (properties.keys.contains("id")) throw IllegalArgumentException(
        "This method can't be used for query by id\n" +
                "Please use function with following signature instead - GraphTraversalSource.readObject(id: Long)"
    )
    val map = readObjectAsMap<T>(properties)
    return mapToObject(map, T::class)
}

inline fun <reified T : Model> GraphTraversalSource.deleteObject(id: String): T {
    val obj: T = readObject(id)
    V(id).drop()
    return obj
}

inline fun <reified T : Any> GraphTraversalSource.deleteObject(propertyKey: String, value: Any): T {
    val obj: T = readObject(propertyKey, value)
    V().hasLabel(T::class.qualifiedName)
        .has(propertyKey, value)
        .drop()
    return obj
}

inline fun <reified T : Any> GraphTraversalSource.readObjectMaps(
    propertyKey: String,
    value: String
): List<GraphMap> {
    logger.debug { "Read objects: label: ${T::class.simpleName}, propertyKey: $propertyKey, value: $value" }
    val maps = queryVertexesByPropertyValue(T::class, mapOf(propertyKey to value))
        .next()
        .asMaps()
    logger.debug { "${maps::class}: $maps" }
    @Suppress("UNCHECKED_CAST")
    return maps
}

inline fun <reified T : Any> GraphTraversalSource.readObjectMaps(): List<GraphMap> {
    logger.debug { "Read object: label: ${T::class.simpleName}" }
    val maps = queryVertexesByClass(T::class)
        .next()
        .asMaps()
    logger.debug { "${maps::class}: $maps" }
    @Suppress("UNCHECKED_CAST")
    return maps
}

inline fun <reified T : Any> GraphTraversalSource.readObjects(
    propertyKey: String,
    value: String
): List<T> = readObjectMaps<T>(propertyKey, value)
    .map { mapToObject(it, T::class) }

inline fun <reified T : Model> GraphTraversalSource.readObjects(): List<T> = readObjectMaps<T>()
    .map { mapToObject(it, T::class) }

inline fun <reified T : Model> GraphTraversalSource.queryVertexesById(
    id: String,
): GraphTraversal<Vertex, Tree<Any>> {
    val (_, serializable, lists) = splitProperties(T::class)
    return if (serializable.isEmpty() && lists.isEmpty()) {
        V(id).tree().by(elementMap<Element, Any>())
    } else {
        V(id).inflateEdges()
    }
}

/**
 * Base list query function
 */
fun GraphTraversalSource.queryVertexes(
    limit: Long = 50L,
    depth: Int = 3,
    condition: (GraphTraversal<Vertex, Vertex>) -> GraphTraversal<Vertex, Vertex>
): GraphTraversal<Vertex, Tree<Any>> = V()
    .let(condition)
    .limit(limit)
    .inflateEdges(depth)


fun GraphTraversal<Vertex, Vertex>.inflateEdges(depth: Int = 3): GraphTraversal<Vertex, Tree<Any>> {
    /**
     *  "c" - shortcut for "credentials.`__`"
     *  used to resolve name conflict between GraphTraversal.outE and __.outE which caused SO error
     */
    return repeat(c.outE().inV())
        .times(depth)
        .emit()
        .tree()
        .by(elementMap<Element, Any>())
}

fun GraphTraversalSource.queryVertexesByClass(
    cls: KClass<*>,
    limit: Long = 50
): GraphTraversal<Vertex, Tree<Any>> = queryVertexes(limit = limit) { traversal ->
    traversal.hasLabel(cls.qualifiedName)
}

fun GraphTraversalSource.queryVertexesByPropertyValue(
    cls: KClass<*>,
    properties: Map<String, Any>,
): GraphTraversal<Vertex, Tree<Any>> {
    val (_, serializable, lists) = splitProperties(cls)
    return if (serializable.isEmpty() && lists.isEmpty()) {
        var labeled = V().hasLabel(cls.qualifiedName)
        properties.forEach { (key, value) ->
            labeled = labeled.has(key, value)
        }
        labeled
            .tree()
            .by(elementMap<Element, Any>())
    } else {
        queryVertexes { traversal ->
            var labeled = traversal
                .hasLabel(cls.qualifiedName)
            properties.forEach { (key, value) ->
                labeled = labeled.has(key, value)
            }
            labeled
        }
    }
}

inline fun <reified T : Any> GraphTraversalSource.readObjectAsMap(
    properties: Map<String, Any>,
): GraphMap {
    val label = T::class.qualifiedName
    logger.debug { "Read object: label: $label, ${properties.map { (k, v) -> "Property $k : $v" }.joinToString()}" }
    val map = queryVertexesByPropertyValue(T::class, properties)
        .next()
        .asMaps()[0]
    logger.debug { "${map::class}: $map" }
    @Suppress("UNCHECKED_CAST")
    return map
}

inline fun <reified T : Model> GraphTraversalSource.readObjectAsMap(
    id: String
): GraphMap {
    val label = T::class.qualifiedName
    logger.debug { "Read object: label: $label, id: $id" }
    val map = queryVertexesById<T>(id)
        .next()
        .asMaps()[0]
    logger.debug { "${map::class}: $map" }
    return map
}

fun time(): String = now().toString()
