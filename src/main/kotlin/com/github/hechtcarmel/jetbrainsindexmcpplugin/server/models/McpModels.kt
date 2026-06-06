package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean = false
)

@Serializable
@JsonClassDiscriminator("type")
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String
    ) : ContentBlock()
}
