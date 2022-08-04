package com.stepango.kremlin

import kotlinx.serialization.Serializable

typealias Id = String

/**
 * Vertext::id returns Long or String value based on DB
 * It can be used to efficiently query data
 */
@Serializable
abstract class Model {
    /**
     * Actual type of DB ID's is unknown, so String is used
     */
    abstract val id: Id

}

@Serializable
abstract class ModelUpdatable : Model() {
    abstract val createdAt: String
    abstract val updatedAt: String
}