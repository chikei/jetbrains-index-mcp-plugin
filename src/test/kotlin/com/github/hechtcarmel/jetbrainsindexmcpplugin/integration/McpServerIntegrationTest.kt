package com.github.hechtcarmel.jetbrainsindexmcpplugin.integration

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Integration tests for tool registration and dispatch.
 *
 * Protocol-level behavior (initialize, ping, tools/list routing, JSON-RPC error codes) is now
 * owned by the MCP SDK and is exercised over the wire in `KtorMcpServerUnitTest`. These tests
 * cover the IDE-specific pieces: which tools the registry advertises, and dispatching a real
 * tool call through [McpToolDispatcher].
 */
class McpServerIntegrationTest : BasePlatformTestCase() {

    private lateinit var dispatcher: McpToolDispatcher
    private lateinit var toolRegistry: ToolRegistry

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        dispatcher = McpToolDispatcher()
    }

    private fun registeredToolNames(): List<String> = toolRegistry.getToolDefinitions().map { it.name }

    // Tool registration tests

    fun testRegistryAdvertisesTools() {
        val toolsCount = 11
        assertTrue("Should have at least $toolsCount tools", registeredToolNames().size >= toolsCount)
    }

    fun testRegistryContainsNavigationTools() {
        val toolNames = registeredToolNames()

        // Note: ide_find_symbol and ide_file_structure are disabled by default, so not included here
        val expectedNavigationTools = listOf(
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.SEARCH_TEXT
        )

        expectedNavigationTools.forEach { toolName ->
            assertTrue("Should contain $toolName tool", toolNames.contains(toolName))
        }
    }

    fun testRegistryContainsIntelligenceTools() {
        assertTrue("Should contain ${ToolNames.DIAGNOSTICS} tool", registeredToolNames().contains(ToolNames.DIAGNOSTICS))
    }

    fun testRegistryContainsProjectTools() {
        assertTrue("Should contain ${ToolNames.INDEX_STATUS} tool", registeredToolNames().contains(ToolNames.INDEX_STATUS))
    }

    // Tool dispatch tests

    fun testDispatchGetIndexStatus() = runBlocking {
        val result = dispatcher.dispatch(
            toolRegistry.getTool(ToolNames.INDEX_STATUS)!!,
            buildJsonObject { }
        )

        assertFalse("${ToolNames.INDEX_STATUS} should not return an error result", result.isError == true)
        assertTrue("${ToolNames.INDEX_STATUS} should return content", result.content.isNotEmpty())
    }

    fun testDispatchReportsErrorForInvalidProjectPath() = runBlocking {
        val result = dispatcher.dispatch(
            toolRegistry.getTool(ToolNames.INDEX_STATUS)!!,
            buildJsonObject { put("project_path", "/non/existent/project/path") }
        )

        assertTrue("Invalid project_path should yield an error result", result.isError == true)
    }
}
