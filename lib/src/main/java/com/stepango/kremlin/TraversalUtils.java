package com.stepango.kremlin;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class TraversalUtils {
    /**
     * Kotlin cant resolve overloaded java methods named `to` so we rename overloads in java
     */
    public static <S, E> GraphTraversal<S, E> toVertex(
            final GraphTraversal<S, E> traversal,
            final Traversal<?, Vertex> toVertex) {
        return traversal.to(toVertex);
    }

    /**
     * Kotlin cant resolve overloaded java methods named `to` so we rename overloads in java
     */
    public static <S, E> GraphTraversal<S, E> toVertex(
            final GraphTraversal<S, E> traversal,
            final Vertex toVertex) {
        return traversal.to(toVertex);
    }
}
