package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import junit.framework.TestCase

/**
 * Unit tests for [toCallToolResult]: mapping the plugin's [ToolCallResult] to the SDK's
 * `CallToolResult`, including content-block conversion and the `isError` flag.
 */
class McpToolDispatcherMappingUnitTest : TestCase() {

    fun testMapsTextContent() {
        val result = toCallToolResult(
            ToolCallResult(content = listOf(ContentBlock.Text("hello")), isError = false)
        )

        assertEquals(false, result.isError)
        val block = result.content.single()
        assertTrue("Text block should map to SDK TextContent", block is TextContent)
        assertEquals("hello", (block as TextContent).text)
    }

    fun testMapsImageContent() {
        val result = toCallToolResult(
            ToolCallResult(content = listOf(ContentBlock.Image("base64data==", "image/png")))
        )

        val block = result.content.single()
        assertTrue("Image block should map to SDK ImageContent", block is ImageContent)
        val image = block as ImageContent
        assertEquals("base64data==", image.data)
        assertEquals("image/png", image.mimeType)
    }

    fun testPreservesIsErrorFlag() {
        val result = toCallToolResult(
            ToolCallResult(content = listOf(ContentBlock.Text("boom")), isError = true)
        )

        assertEquals(true, result.isError)
    }

    fun testMapsEmptyContentToEmptyList() {
        val result = toCallToolResult(
            ToolCallResult(content = emptyList())
        )

        assertTrue("Empty content should map to an empty list", result.content.isEmpty())
        assertEquals(false, result.isError)
    }

    fun testDefaultsIsErrorToFalse() {
        val result = toCallToolResult(
            ToolCallResult(content = listOf(ContentBlock.Text("ok")))
        )

        assertEquals("isError should default to false when unset", false, result.isError)
    }

    fun testMapsMultipleBlocksInOrder() {
        val result = toCallToolResult(
            ToolCallResult(
                content = listOf(
                    ContentBlock.Text("first"),
                    ContentBlock.Image("img", "image/jpeg")
                )
            )
        )

        assertEquals(2, result.content.size)
        assertTrue(result.content[0] is TextContent)
        assertTrue(result.content[1] is ImageContent)
    }
}
