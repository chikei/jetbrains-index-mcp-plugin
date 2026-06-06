package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Regression coverage: a failure inside command-history recording/updating must not break the
 * tool call itself. The dispatcher swallows history failures via its `record`/`update` hooks.
 */
class McpToolDispatcherHistoryFailureTest : BasePlatformTestCase() {

    fun testToolCallStillSucceedsWhenHistoryRecordingFails() = runBlocking {
        val dispatcher = McpToolDispatcher(
            recordHistory = { _, _ -> error("history record failure") }
        )

        val result = dispatcher.dispatch(TestTool(), buildJsonObject { })

        assertFalse("Tool result should still succeed", result.isError == true)
        assertEquals("ok", (result.content.single() as TextContent).text)
    }

    fun testToolCallStillSucceedsWhenHistoryUpdateFails() = runBlocking {
        val dispatcher = McpToolDispatcher(
            updateHistory = { _, _, _, _, _ -> error("history update failure") }
        )

        val result = dispatcher.dispatch(TestTool(), buildJsonObject { })

        assertFalse("Tool result should still succeed", result.isError == true)
        assertEquals("ok", (result.content.single() as TextContent).text)
    }

    private class TestTool : McpTool {
        override val name: String = NAME
        override val description: String = "Test tool for history failure regression coverage"
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
        }

        override suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
            return ToolCallResult(
                content = listOf(ContentBlock.Text("ok"))
            )
        }

        companion object {
            const val NAME = "ide_test_history_failure"
        }
    }
}
