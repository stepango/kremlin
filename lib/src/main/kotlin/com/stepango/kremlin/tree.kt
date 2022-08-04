/**
 * I know what I'm doing
 */
@file:Suppress("UNCHECKED_CAST")

package com.stepango.kremlin

import org.apache.tinkerpop.gremlin.process.traversal.step.util.Tree

const val LABEL_INDEX = 1

/**
 * Also used as key
 */
val Tree<Any>.allProperties: Set<Map<Any, Any>>
    get() = keys as Set<Map<Any, Any>>? ?: emptySet()

/**
 * filter out 'label' key from map, since we already store it as a Class information
 */
fun Map<Any, Any>.asProperties(): GraphMap {
    val result = this
        .mapKeys { it.key.toString() }
        .mapValues { if (it.value == "null_value") null else it.value }
            as MutableMap<String, Any?>
    // Local gremlin server and neptune have different types for id fields, so we just cast it to string
    result["id"] = result["id"].toString()
    return result
}

private data class Edge(
    val label: String,
    val tree: Tree<Any>,
)

/**
 * Edges transformed to nested maps e.g. nested objects
 * label is the name of the field
 */
private fun Tree<Any>.edges(key: Map<*, *>): List<Edge> {
    val tree = this[key]
    val k = tree?.keys ?: return emptyList()
    return k.map {
        it as Map<Any, Any>
        val label = it.values.toList()[LABEL_INDEX].toString()
        Edge(label, tree[it] as Tree<Any>? ?: Tree())
    }
}

typealias ListMap = ArrayList<GraphMap>

fun Tree<Any>.asMaps(): List<GraphMap> {
    val list = mutableListOf<GraphMap>()
    allProperties.forEach {
        val map = mutableMapOf<String, Any?>()
        map += it.asProperties()
        edges(it).forEach { e ->
            val maps = e.tree.asMaps()
            if (e.label.endsWith("List")) {
                map.apply { putIfAbsent(e.label, ListMap()) }
                (map[e.label] as ListMap).add(maps[0])
            } else {
                map[e.label] = maps[0]
            }
        }
        list.add(map)
    }

    return list
}