package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun toToolSchema(schema: JsonObject): ToolSchema {
    val properties = schema["properties"]?.jsonObject
    val required = schema["required"]?.jsonArray
        ?.map { it.jsonPrimitive.content }
        ?: emptyList()
    return ToolSchema(properties = properties, required = required)
}
