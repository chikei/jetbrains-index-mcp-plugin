package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Platform-dependent tests for workspace project resolution.
 * Tests that the MCP dispatcher correctly resolves projects in workspace scenarios
 * where sub-projects are represented as modules with different content roots.
 */
class WorkspaceResolutionTest : BasePlatformTestCase() {

    private lateinit var dispatcher: McpToolDispatcher
    private lateinit var toolRegistry: ToolRegistry
    private var originalAvailableProjectsMode: McpSettings.AvailableProjectsMode? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry()
        toolRegistry.registerBuiltInTools()
        dispatcher = McpToolDispatcher()
        originalAvailableProjectsMode = McpSettings.getInstance().availableProjectsMode
    }

    override fun tearDown() {
        try {
            originalAvailableProjectsMode?.let { McpSettings.getInstance().availableProjectsMode = it }
        } finally {
            super.tearDown()
        }
    }

    private suspend fun callIndexStatus(arguments: JsonObject): CallToolResult =
        dispatcher.dispatch(toolRegistry.getTool(ToolNames.INDEX_STATUS)!!, arguments)

    /**
     * Tests that a tool call resolves correctly when project_path matches
     * a module content root (simulating workspace sub-project access).
     */
    fun testToolCallWithModuleContentRootPath() = runBlocking {
        val contentRoots = ProjectUtils.getModuleContentRoots(project)
        if (contentRoots.isEmpty()) return@runBlocking

        val result = callIndexStatus(buildJsonObject { put("project_path", contentRoots.first()) })
        assertFalse("Tool should succeed with module content root path", result.isError == true)
    }

    /**
     * Tests that a tool call resolves correctly when project_path is a
     * subdirectory of an open project's basePath.
     */
    fun testToolCallWithSubdirectoryOfProject() = runBlocking {
        val projectPath = project.basePath ?: return@runBlocking

        val result = callIndexStatus(buildJsonObject { put("project_path", "$projectPath/src") })
        assertFalse("Tool should succeed with subdirectory of project", result.isError == true)
    }

    /**
     * Tests that an invalid path still returns a proper error with available_projects.
     */
    fun testInvalidPathReturnsAvailableProjects() = runBlocking {
        val errorJson = requestInvalidPathErrorJson()

        assertEquals("project_not_found", errorJson["error"]?.jsonPrimitive?.content)
        assertNotNull("Should include available_projects", errorJson["available_projects"])

        val availableProjects = errorJson["available_projects"]!!.jsonArray
        assertTrue("available_projects should not be empty", availableProjects.isNotEmpty())
    }

    fun testCompactAvailableProjectsModeOmitsWorkspaceSubProjects() = runBlocking {
        val extraContentRoot = addWorkspaceSubProjectContentRoot()
        McpSettings.getInstance().availableProjectsMode = McpSettings.AvailableProjectsMode.COMPACT

        val errorJson = requestInvalidPathErrorJson()
        val availableProjects = errorJson["available_projects"]!!.jsonArray
        val availableProjectPaths = availableProjects.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }

        assertTrue("Top-level project root should still be returned", availableProjectPaths.contains(project.basePath))
        assertFalse("Compact mode should omit workspace sub-project entries", availableProjectPaths.contains(extraContentRoot.path))
        assertTrue(
            "Compact mode should omit workspace metadata from project entries",
            availableProjects.none { it.jsonObject.containsKey("workspace") }
        )
    }

    /**
     * Tests that ProjectUtils.getModuleContentRoots returns at least one root
     * for a project with modules.
     */
    fun testGetModuleContentRootsReturnsRoots() {
        val roots = ProjectUtils.getModuleContentRoots(project)
        assertNotNull("Content roots should not be null", roots)
        // In a test fixture, there should be at least one content root
        assertTrue("Should have at least one content root", roots.isNotEmpty())
    }

    /**
     * Tests that ProjectUtils.isProjectFile correctly identifies files
     * under module content roots.
     */
    fun testIsProjectFileWorksWithContentRoots() {
        val roots = ProjectUtils.getModuleContentRoots(project)
        if (roots.isEmpty()) return

        val testFile = myFixture.addFileToProject("TestFile.txt", "test content")
        val virtualFile = testFile.virtualFile

        assertTrue(
            "File under content root should be recognized as project file",
            ProjectUtils.isProjectFile(project, virtualFile)
        )
    }

    private fun requestInvalidPathErrorJson() = runBlocking {
        val result = callIndexStatus(buildJsonObject { put("project_path", "/completely/invalid/path") })

        assertTrue("Tool should return error for completely invalid path", result.isError == true)

        return@runBlocking json.parseToJsonElement((result.content.first() as TextContent).text).jsonObject
    }

    private fun addWorkspaceSubProjectContentRoot(): VirtualFile {
        val contentRoot = myFixture.tempDirFixture.findOrCreateDir("workspace-subproject")
        PsiTestUtil.addContentRoot(module, contentRoot)
        return contentRoot
    }
}
