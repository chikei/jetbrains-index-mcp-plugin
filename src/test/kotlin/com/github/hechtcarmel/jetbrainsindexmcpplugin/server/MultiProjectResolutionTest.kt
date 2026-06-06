package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests for multi-project resolution through [McpToolDispatcher].
 * For schema validation tests that don't need the platform, see ToolsUnitTest.
 */
class MultiProjectResolutionTest : BasePlatformTestCase() {

    private lateinit var dispatcher: McpToolDispatcher
    private lateinit var toolRegistry: ToolRegistry

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        dispatcher = McpToolDispatcher()
    }

    private suspend fun callIndexStatus(arguments: JsonObject): CallToolResult =
        dispatcher.dispatch(toolRegistry.getTool(ToolNames.INDEX_STATUS)!!, arguments)

    fun testToolCallWithSingleProject() = runBlocking {
        val result = callIndexStatus(buildJsonObject { })
        assertFalse("Tool should succeed with single project", result.isError == true)
    }

    fun testToolCallWithExplicitProjectPath() = runBlocking {
        val result = callIndexStatus(buildJsonObject { put("project_path", project.basePath ?: "") })
        assertFalse("Tool should succeed with explicit project_path", result.isError == true)
    }

    fun testToolCallWithInvalidProjectPath() = runBlocking {
        val result = callIndexStatus(buildJsonObject { put("project_path", "/non/existent/project/path") })

        assertTrue("Tool should return error for invalid project_path", result.isError == true)

        val errorJson = json.parseToJsonElement((result.content.first() as TextContent).text).jsonObject
        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])
    }
}
