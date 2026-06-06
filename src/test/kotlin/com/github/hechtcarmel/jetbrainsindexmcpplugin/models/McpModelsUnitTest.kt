package com.github.hechtcarmel.jetbrainsindexmcpplugin.models

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolDefinition
import junit.framework.TestCase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Covers the tool-facing models that survived the MCP SDK migration: [ContentBlock],
 * [ToolCallResult], and [ToolDefinition]. Protocol-level models (initialize result, server
 * capabilities, tools-list result) are now owned by the SDK and no longer live here.
 */
class McpModelsUnitTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ContentBlock serialization tests

    fun testTextContentBlockSerialization() {
        val textBlock = ContentBlock.Text("Hello, World!")

        val serialized = json.encodeToString<ContentBlock>(textBlock)
        val deserialized = json.decodeFromString<ContentBlock>(serialized)

        assertTrue("Should deserialize to Text block", deserialized is ContentBlock.Text)
        assertEquals("Hello, World!", (deserialized as ContentBlock.Text).text)
        assertTrue("Serialized should contain type discriminator", serialized.contains("\"type\":\"text\""))
    }

    fun testImageContentBlockSerialization() {
        val imageBlock = ContentBlock.Image("base64data==", "image/png")

        val serialized = json.encodeToString<ContentBlock>(imageBlock)
        val deserialized = json.decodeFromString<ContentBlock>(serialized)

        assertTrue("Should deserialize to Image block", deserialized is ContentBlock.Image)
        val image = deserialized as ContentBlock.Image
        assertEquals("base64data==", image.data)
        assertEquals("image/png", image.mimeType)
        assertTrue("Serialized should contain type discriminator", serialized.contains("\"type\":\"image\""))
    }

    fun testContentBlockPolymorphicDeserialization() {
        val textJson = """{"type":"text","text":"test content"}"""
        val imageJson = """{"type":"image","data":"abc123","mimeType":"image/jpeg"}"""

        val textBlock = json.decodeFromString<ContentBlock>(textJson)
        val imageBlock = json.decodeFromString<ContentBlock>(imageJson)

        assertTrue("Text JSON should deserialize to Text", textBlock is ContentBlock.Text)
        assertTrue("Image JSON should deserialize to Image", imageBlock is ContentBlock.Image)
    }

    // ToolCallResult tests

    fun testToolCallResultWithTextContent() {
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text("Result text")),
            isError = false
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ToolCallResult>(serialized)

        assertFalse("isError should be false", deserialized.isError)
        assertEquals(1, deserialized.content.size)
        assertTrue(deserialized.content[0] is ContentBlock.Text)
    }

    fun testToolCallResultWithError() {
        val result = ToolCallResult(
            content = listOf(ContentBlock.Text("Error message")),
            isError = true
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ToolCallResult>(serialized)

        assertTrue("isError should be true", deserialized.isError)
    }

    fun testToolCallResultWithMultipleContentBlocks() {
        val result = ToolCallResult(
            content = listOf(
                ContentBlock.Text("Text content"),
                ContentBlock.Image("imagedata", "image/png")
            ),
            isError = false
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<ToolCallResult>(serialized)

        assertEquals(2, deserialized.content.size)
        assertTrue(deserialized.content[0] is ContentBlock.Text)
        assertTrue(deserialized.content[1] is ContentBlock.Image)
    }

    // ToolDefinition tests

    fun testToolDefinitionSerialization() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("file", buildJsonObject { put("type", "string") })
            })
        }

        val definition = ToolDefinition(
            name = "test_tool",
            description = "A test tool",
            inputSchema = schema
        )

        val serialized = json.encodeToString(definition)
        val deserialized = json.decodeFromString<ToolDefinition>(serialized)

        assertEquals("test_tool", deserialized.name)
        assertEquals("A test tool", deserialized.description)
        assertNotNull(deserialized.inputSchema)
    }
}
